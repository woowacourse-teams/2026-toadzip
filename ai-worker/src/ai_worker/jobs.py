"""ai_jobs 큐의 SQL 전부. 전이 규칙은 contract/README.md를 따른다.

연결은 autocommit이어야 한다. 함수마다 문장 하나라 그 자리에서 커밋되고,
꺼낸 작업은 추출을 시작하기 전에 다른 워커에게 RUNNING으로 보인다.
시각은 모두 DB now()로 정한다.
"""

from dataclasses import dataclass
from datetime import timedelta
from uuid import UUID

import psycopg
from psycopg.rows import class_row

ERROR_CODE_MAX = 50
ERROR_MESSAGE_MAX = 500
LEASE_EXPIRED_CODE = "LEASE_EXPIRED"
LEASE_EXPIRED_MESSAGE = "처리 시간이 초과되어 작업을 끝냈습니다. 다시 요청해 주세요."
DEADLINE_EXCEEDED_CODE = "DEADLINE_EXCEEDED"
DEADLINE_EXCEEDED_MESSAGE = "요청한 지 오래되어 작업을 끝냈습니다. 다시 요청해 주세요."


@dataclass(frozen=True)
class Job:
    """꺼낸 작업 하나. lease_token은 완료·실패·재시도 쓰기의 조건이다."""

    id: int
    kind: str
    announcement_id: int
    file_name: str
    pdf: bytes
    input: dict | None
    attempts: int
    lease_token: UUID


def fail_exhausted_leases(conn: psycopg.Connection, max_attempts: int) -> int:
    """lease가 끝났는데 시도를 다 쓴 RUNNING을 FAILED로 끝낸다. 꺼내기는 이 작업을 건너뛴다."""
    cursor = conn.execute(
        """
        UPDATE ai_jobs
        SET status = 'FAILED', pdf = NULL, error_code = %s, error_message = %s,
            lease_token = NULL, lease_until = NULL, completed_at = now()
        WHERE status = 'RUNNING' AND lease_until <= now() AND attempts >= %s
        """,
        (LEASE_EXPIRED_CODE, LEASE_EXPIRED_MESSAGE, max_attempts),
    )
    return cursor.rowcount


def fail_overdue(conn: psycopg.Connection, deadline: timedelta) -> int:
    """넣은 지 deadline이 지난 진행 중 작업을 상태와 관계없이 FAILED로 끝낸다. 처리 중인 워커의 쓰기는 lease_token이 비어 0행이 된다."""
    cursor = conn.execute(
        """
        UPDATE ai_jobs
        SET status = 'FAILED', pdf = NULL, error_code = %s, error_message = %s,
            lease_token = NULL, lease_until = NULL, completed_at = now()
        WHERE status IN ('PENDING', 'RUNNING', 'RETRY') AND created_at <= now() - %s
        """,
        (DEADLINE_EXCEEDED_CODE, DEADLINE_EXCEEDED_MESSAGE, deadline),
    )
    return cursor.rowcount


def claim(conn: psycopg.Connection, lease: timedelta, deadline: timedelta, max_attempts: int) -> Job | None:
    """마감 전인 작업 하나를 잡아 RUNNING으로 바꾸고 시도 횟수를 하나 올린다. 없으면 None.
    started_at은 첫 시작 시각을 남기고, 직전 재시도 사유(error_code·error_message)는 다음 기록까지 둔다."""
    with conn.cursor(row_factory=class_row(Job)) as cursor:
        cursor.execute(
            """
            WITH next AS (
                SELECT id FROM ai_jobs
                WHERE ((status IN ('PENDING', 'RETRY') AND next_attempt_at <= now())
                   OR (status = 'RUNNING' AND lease_until <= now() AND attempts < %s))
                  AND created_at > now() - %s
                ORDER BY id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE ai_jobs AS job
            SET status = 'RUNNING', lease_token = gen_random_uuid(), lease_until = now() + %s,
                attempts = job.attempts + 1, started_at = COALESCE(job.started_at, now())
            FROM next
            WHERE job.id = next.id
            RETURNING job.id, job.kind, job.announcement_id, job.file_name, job.pdf, job.input,
                job.attempts, job.lease_token
            """,
            (max_attempts, deadline, lease),
        )
        return cursor.fetchone()


def extend_lease(conn: psycopg.Connection, job: Job, lease: timedelta, deadline: timedelta) -> bool:
    """처리 중인 작업의 lease를 now() + lease로 늘린다. lease를 빼앗겼거나 작업이 끝났거나 마감이 지났으면 False.
    마감이 지나면 늘리지 않아, 멈춘 추출이 lease를 붙잡고 있어도 다른 워커의 마감 정리가 그 작업을 끝낼 수 있다."""
    cursor = conn.execute(
        "UPDATE ai_jobs SET lease_until = now() + %s WHERE id = %s AND lease_token = %s AND created_at > now() - %s",
        (lease, job.id, job.lease_token, deadline),
    )
    return cursor.rowcount == 1


def complete(conn: psycopg.Connection, job: Job, result_json: str) -> bool:
    """JSON 객체 문자열인 결과를 쓰고 PDF를 비운다. lease를 빼앗겼거나 작업이 지워졌으면 False."""
    cursor = conn.execute(
        """
        UPDATE ai_jobs
        SET status = 'SUCCEEDED', result = %s::jsonb, pdf = NULL, error_code = NULL, error_message = NULL,
            lease_token = NULL, lease_until = NULL, completed_at = now()
        WHERE id = %s AND lease_token = %s
        """,
        (result_json, job.id, job.lease_token),
    )
    return cursor.rowcount == 1


def fail(conn: psycopg.Connection, job: Job, code: str, message: str) -> bool:
    """실패로 끝내고 PDF를 비운다. message는 관리자에게 보이는 공개 문구다."""
    code, message = public_text(code, ERROR_CODE_MAX), public_text(message, ERROR_MESSAGE_MAX)
    cursor = conn.execute(
        """
        UPDATE ai_jobs
        SET status = 'FAILED', pdf = NULL, error_code = %s, error_message = %s,
            lease_token = NULL, lease_until = NULL, completed_at = now()
        WHERE id = %s AND lease_token = %s
        """,
        (code, message, job.id, job.lease_token),
    )
    return cursor.rowcount == 1


def retry(conn: psycopg.Connection, job: Job, delay: timedelta, claim_window: timedelta, code: str, message: str) -> bool:
    """PDF를 남긴 채 delay 뒤에 다시 꺼내게 한다. 재시도 사유는 관리자 화면이 기다리는 동안 보여 준다.
    예약 시각이 꺼내기 기한(넣은 뒤 claim_window)을 넘으면 claim이 다시 꺼내지 않으므로 쓰지 않고 False다.
    lease를 빼앗겼거나 작업이 지워졌어도 False."""
    code, message = public_text(code, ERROR_CODE_MAX), public_text(message, ERROR_MESSAGE_MAX)
    cursor = conn.execute(
        """
        UPDATE ai_jobs
        SET status = 'RETRY', next_attempt_at = now() + %s, lease_token = NULL, lease_until = NULL,
            error_code = %s, error_message = %s
        WHERE id = %s AND lease_token = %s AND now() + %s < created_at + %s
        """,
        (delay, code, message, job.id, job.lease_token, delay, claim_window),
    )
    return cursor.rowcount == 1


def public_text(text: str, limit: int) -> str:
    """열 길이에 맞춘다. 문구는 추출기가 정한 고정 문구지만, Postgres 문자열은 NUL을 담지 못해 기록 자체가 거절되므로 뺀다."""
    return text.replace("\x00", "")[:limit]

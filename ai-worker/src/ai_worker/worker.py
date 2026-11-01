"""작업 하나를 꺼내 추출하고 결과를 기록한다."""

import json
import logging
from datetime import timedelta

import psycopg

from ai_worker import jobs
from ai_worker.extraction import Extractor, PermanentError, TransientError

log = logging.getLogger(__name__)

# DeepSeek와 Jev 호출 시간 초과의 합보다 길어야 한다. heartbeat가 없어 이 안에 끝나지 않으면 다른 워커가 다시 꺼낸다.
LEASE = timedelta(minutes=10)
# n번째 시도가 일시 오류면 BACKOFF[n-1] 뒤에 다시 꺼낸다.
BACKOFF = (timedelta(minutes=1), timedelta(minutes=5))
INTERNAL_ERROR_CODE = "INTERNAL_ERROR"
INTERNAL_ERROR_MESSAGE = "추출 중 알 수 없는 오류가 났습니다. 다시 요청해 주세요."


def run_once(conn: psycopg.Connection, extractor: Extractor) -> bool:
    """작업 하나를 처리한다. 꺼낼 작업이 없으면 False."""
    if not conn.autocommit:
        # 꺼내기가 추출 전에 커밋되지 않으면 추출 내내 다른 워커와 Spring에 PENDING으로 보인다.
        raise ValueError("ai_jobs 연결은 autocommit이어야 합니다.")
    expired = jobs.fail_exhausted_leases(conn)
    if expired:
        log.warning("lease가 끝난 채 시도를 다 쓴 작업 %d건을 실패로 끝냈습니다.", expired)

    job = jobs.claim(conn, LEASE)
    if job is None:
        return False

    try:
        result = serialize(extractor.extract(job))
    except TransientError as error:
        if job.attempts < job.max_attempts:
            log.warning("작업 %d 시도 %d 일시 오류(%s), 재시도합니다.", job.id, job.attempts, error.code)
            written = jobs.retry(conn, job, BACKOFF[min(job.attempts, len(BACKOFF)) - 1])
        else:
            log.warning("작업 %d 시도 %d 일시 오류(%s), 시도를 다 써서 실패로 끝냅니다.", job.id, job.attempts, error.code)
            written = jobs.fail(conn, job, error.code, error.message)
    except PermanentError as error:
        log.warning("작업 %d 실패(%s).", job.id, error.code)
        written = jobs.fail(conn, job, error.code, error.message)
    except Exception:
        log.exception("작업 %d 추출 중 예상하지 못한 오류.", job.id)
        written = jobs.fail(conn, job, INTERNAL_ERROR_CODE, INTERNAL_ERROR_MESSAGE)
    else:
        try:
            written = jobs.complete(conn, job, result)
        except (psycopg.DataError, UnicodeEncodeError):
            # NUL·짝 없는 서로게이트처럼 jsonb가 거절하는 결과. 잡지 않으면 lease 만료까지 RUNNING으로 남아 유료 추출을 다시 부른다.
            log.exception("작업 %d 결과를 jsonb로 기록하지 못했습니다.", job.id)
            written = jobs.fail(conn, job, INTERNAL_ERROR_CODE, INTERNAL_ERROR_MESSAGE)

    if not written:
        log.warning("작업 %d 기록이 0행입니다. lease를 빼앗겼거나 공고와 함께 지워졌습니다.", job.id)
    return True


def serialize(result: object) -> str:
    """결과를 기록 전에 JSON 객체 문자열로 만든다. 기록 단계에서 실패하면 작업이 lease 만료까지 RUNNING에 묶인다."""
    if not isinstance(result, dict):
        raise TypeError(f"추출 결과는 JSON 객체여야 합니다: {type(result).__name__}")
    return json.dumps(result, ensure_ascii=False)

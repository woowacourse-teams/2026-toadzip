"""작업을 꺼내 추출하고 결과를 기록한다. run은 종료 신호가 올 때까지 run_once를 되풀이한다."""

import json
import logging
import os
import threading
from collections.abc import Callable
from contextlib import contextmanager
from datetime import timedelta
from pathlib import Path

import psycopg

from ai_worker import jobs
from ai_worker.extraction import ATTEMPT_BUDGET, Extractor, PermanentError, TransientError

log = logging.getLogger(__name__)

# 추출하는 동안 HEARTBEAT마다 lease를 LEASE만큼 늘린다. 워커가 죽으면 늘리기가 멈춰 LEASE 뒤에 다른 워커가 다시 꺼낸다.
LEASE = timedelta(minutes=3)
HEARTBEAT = timedelta(minutes=1)
# 넣은 지 이만큼 지난 작업은 어느 상태든 실패로 끝낸다. 관리자 화면은 이보다 조금 더 기다린다(extractionApi.ts JOB_WAIT_LIMIT_MS).
DEADLINE = timedelta(minutes=40)
# n번째 시도가 일시 오류면 BACKOFF[n-1] 뒤에 다시 꺼낸다.
BACKOFF = (timedelta(minutes=1), timedelta(minutes=5))
INTERNAL_ERROR_CODE = "INTERNAL_ERROR"
INTERNAL_ERROR_MESSAGE = "추출 중 알 수 없는 오류가 났습니다. 다시 요청해 주세요."
# 꺼낼 작업이 없을 때와 DB 연결이 끊겼을 때 기다리는 시간.
IDLE = timedelta(seconds=5)
RECONNECT = timedelta(seconds=10)
# 설정하면 DB에 붙어 일하는 동안 이 파일을 계속 갱신한다. 컨테이너 healthcheck가 갱신 시각을 본다(Dockerfile).
ALIVE_FILE = os.environ.get("AI_WORKER_ALIVE_FILE")


def mark_alive() -> None:
    """DB 접속이 실패하면 재연결만 되풀이해 프로세스는 살아 있으므로, 실제로 큐를 처리하고 있다는 신호를 따로 남긴다."""
    if not ALIVE_FILE:
        return
    try:
        Path(ALIVE_FILE).touch()
    except OSError:
        # heartbeat 안에서 터지면 lease 연장이 멈춰 다른 워커가 유료 호출을 다시 한다. 건강 신호만 놓치게 둔다.
        log.exception("살아 있음 파일을 갱신하지 못했습니다.")


def run(connect: Callable[[], psycopg.Connection], extractor: Extractor, stop: threading.Event) -> None:
    """stop이 켜질 때까지 작업을 처리한다. 처리 중인 작업은 끝까지 하고 멈춘다.
    run_once가 실패해도 워커를 끝내지 않는다. 끝내면 남은 작업이 모두 마감까지 멈추기 때문이다."""
    while not stop.is_set():
        try:
            with connect() as conn:
                while not stop.is_set():
                    try:
                        worked = run_once(conn, extractor)
                    except Exception:
                        log.exception("작업 처리 중 오류가 났습니다.")
                        if conn.broken or conn.closed:
                            break
                        worked = False
                    if not worked:
                        stop.wait(IDLE.total_seconds())
        except psycopg.OperationalError:
            log.exception("DB에 연결하지 못했습니다. %d초 뒤 다시 연결합니다.", RECONNECT.total_seconds())
        if not stop.is_set():
            stop.wait(RECONNECT.total_seconds())


def run_once(conn: psycopg.Connection, extractor: Extractor) -> bool:
    """작업 하나를 처리한다. 꺼낼 작업이 없으면 False."""
    if not conn.autocommit:
        # 꺼내기가 추출 전에 커밋되지 않으면 추출 내내 다른 워커와 Spring에 PENDING으로 보인다.
        raise ValueError("ai_jobs 연결은 autocommit이어야 합니다.")
    overdue = jobs.fail_overdue(conn, DEADLINE)
    if overdue:
        log.warning("마감이 지난 작업 %d건을 실패로 끝냈습니다.", overdue)
    expired = jobs.fail_exhausted_leases(conn)
    if expired:
        log.warning("lease가 끝난 채 시도를 다 쓴 작업 %d건을 실패로 끝냈습니다.", expired)

    # 마감 전에 한 번의 추출을 끝낼 수 없는 작업은 꺼내지 않는다. 꺼내면 끝나도 마감 정리에 밀려 유료 호출만 버린다.
    job = jobs.claim(conn, LEASE, DEADLINE - ATTEMPT_BUDGET)
    # 꺼내기까지 됐으면 접속과 큐 권한이 실제로 확인된 것이다. 첫 heartbeat 전에 긴 추출이 시작돼도 healthy로 보인다.
    mark_alive()
    if job is None:
        return False

    try:
        with heartbeat(conn, job):
            result = serialize(extractor.extract(job))
    except TransientError as error:
        if job.attempts < job.max_attempts:
            log.warning("작업 %d 시도 %d 일시 오류(%s), 재시도합니다.", job.id, job.attempts, error.code)
            written = jobs.retry(conn, job, BACKOFF[min(job.attempts, len(BACKOFF)) - 1], error.code, error.message)
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


@contextmanager
def heartbeat(conn: psycopg.Connection, job: jobs.Job):
    """블록이 도는 동안 다른 스레드가 lease를 늘린다. 추출기는 conn을 쓰지 않으므로 두 스레드가 연결을 다투지 않는다."""
    stop = threading.Event()

    def beat():
        while not stop.wait(HEARTBEAT.total_seconds()):
            try:
                if not jobs.extend_lease(conn, job, LEASE, DEADLINE):
                    log.warning("작업 %d lease를 늘리지 못했습니다. 빼앗겼거나 끝났거나 마감이 지났습니다.", job.id)
                    return
                # 긴 추출 중에도 healthy로 보이게 한다.
                mark_alive()
            except psycopg.Error:
                # 일시적인 DB 오류로 멈추면 lease가 끝나 다른 워커가 같은 작업에 유료 호출을 다시 한다. 다음 박자에 다시 늘린다.
                log.exception("작업 %d lease를 늘리다 오류가 났습니다. 다음 박자에 다시 시도합니다.", job.id)

    thread = threading.Thread(target=beat, name=f"heartbeat-{job.id}", daemon=True)
    thread.start()
    try:
        yield
    finally:
        stop.set()
        thread.join()


def serialize(result: object) -> str:
    """결과를 기록 전에 JSON 객체 문자열로 만든다. 기록 단계에서 실패하면 작업이 lease 만료까지 RUNNING에 묶인다."""
    if not isinstance(result, dict):
        raise TypeError(f"추출 결과는 JSON 객체여야 합니다: {type(result).__name__}")
    return json.dumps(result, ensure_ascii=False)

"""`python -m ai_worker`로 워커를 띄운다. SIGTERM·SIGINT를 받으면 처리 중인 작업을 끝내고 멈춘다.

환경변수
- PGHOST·PGPORT·PGDATABASE·PGUSER·PGPASSWORD: toadzip_ai_worker 역할의 Postgres 접속 정보(libpq 표준).
  주소 문자열 대신 쓰는 것은 비밀번호에 특수문자가 있어도 깨지지 않게 하려는 것이다.
- DEEPSEEK_API_KEY: DeepSeek API 키
"""

import logging
import os
import signal
import sys
import threading

import psycopg

from ai_worker.announcement import AnnouncementPdfExtractor
from ai_worker.deepseek import DeepSeek
from ai_worker.worker import run

log = logging.getLogger("ai_worker")


REQUIRED = ("PGHOST", "PGDATABASE", "PGUSER", "PGPASSWORD", "DEEPSEEK_API_KEY")


def connect() -> psycopg.Connection:
    """접속 정보는 PG* 환경변수에서 읽는다. 연결이 조용히 끊겨도 알아채도록 keepalive를 켜고, 한 문장이 30초를 넘으면 끊는다."""
    return psycopg.connect("", autocommit=True, connect_timeout=10, keepalives=1, keepalives_idle=30,
                           keepalives_interval=10, keepalives_count=3, options="-c statement_timeout=30000")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    missing = [name for name in REQUIRED if not os.environ.get(name)]
    if missing:
        sys.exit(f"환경변수가 없습니다: {', '.join(missing)}")
    extractor = AnnouncementPdfExtractor(DeepSeek(os.environ["DEEPSEEK_API_KEY"]))

    stop = threading.Event()

    def request_stop(signum, _frame):
        log.info("종료 신호(%s)를 받았습니다. 처리 중인 작업을 끝내고 멈춥니다.", signal.Signals(signum).name)
        stop.set()

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    log.info("AI 워커를 시작합니다.")
    # 신호 처리기는 메인 스레드에서 돈다. 루프가 메인 스레드에서 stop.wait 중이면 처리기의 stop.set이
    # 같은 잠금을 기다려 멈출 수 있어, 루프는 다른 스레드에서 돌리고 메인 스레드는 기다리기만 한다.
    loop = threading.Thread(target=run, args=(connect, extractor, stop), name="worker")
    loop.start()
    while loop.is_alive():
        loop.join(1)
    if not stop.is_set():
        # 신호 없이 루프가 끝났다면 예상하지 못한 오류로 죽은 것이다. 재시작·감시가 알아채도록 실패로 끝낸다.
        log.error("워커 루프가 오류로 끝났습니다.")
        sys.exit(1)
    log.info("AI 워커를 멈췄습니다.")


if __name__ == "__main__":
    main()

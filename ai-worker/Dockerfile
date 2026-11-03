FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:0.13.6 /uv /bin/uv

WORKDIR /app

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

# 의존성 층을 소스와 나눠 소스만 바뀐 빌드는 의존성을 다시 받지 않는다.
COPY pyproject.toml uv.lock README.md ./
RUN uv sync --locked --no-dev --no-install-project

COPY src ./src
RUN uv sync --locked --no-dev

USER 10001:10001

# 워커는 DB에 붙어 일하는 동안 이 파일을 갱신한다(쉴 때 5초, 추출 중 lease 연장 때 1분마다).
# 접속하지 못하면 갱신이 멈춰 unhealthy가 되고, 릴리스의 compose up --wait가 배포 실패로 알린다.
ENV AI_WORKER_ALIVE_FILE=/tmp/ai-worker-alive
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=3 \
    CMD ["/app/.venv/bin/python", "-c", "import os, sys, time; sys.exit(time.time() - os.path.getmtime(os.environ['AI_WORKER_ALIVE_FILE']) > 150)"]

# SIGTERM을 Python이 직접 받아 처리 중인 작업을 끝내고 멈춘다.
ENTRYPOINT ["/app/.venv/bin/python", "-m", "ai_worker"]

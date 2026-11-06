# AI 워커

Postgres `ai_jobs` 작업 큐를 처리하는 Python 워커다. Spring은 작업을 넣고 결과를 읽으며, 관리자 취소와 마감 정리로만 진행 중 작업을 실패로 끝낸다. 꺼내기·lease·재시도·완료·실패와 PDF 비우기는 워커가 한다. 상태 전이 규칙과 Spring과 맞춘 JSON 형식은 [contract/README.md](contract/README.md)에 있다.

- `src/ai_worker/jobs.py`: `ai_jobs` SQL 전부. 연결은 autocommit이고 시각은 DB `now()`를 쓴다.
- `src/ai_worker/extraction.py`: 추출기 계약. 일시 오류(`TransientError`)는 시도가 남았으면 재시도하고, 그 밖의 오류는 바로 실패로 끝낸다.
- `src/ai_worker/worker.py`: `run_once`가 작업 하나를 꺼내 추출하고 기록한다. 처리하는 동안 1분마다 lease(3분)를 늘리고, 재시도 간격은 1분 → 5분, 넣은 지 40분이 지난 작업은 실패로 끝낸다. 한 번의 추출은 10분(`ATTEMPT_BUDGET`) 안에 끝나야 하고, 마감까지 그만큼 남지 않은 작업은 꺼내지 않는다. `run`은 종료 신호까지 `run_once`를 되풀이하고, 오류가 나도 멈추지 않고 DB 연결이 끊기면 다시 연결한다.
- `src/ai_worker/announcement.py`: 공고문 PDF 추출기. `pages.py`가 고른 쪽의 텍스트와 표를 `openai.py`로 보내고, `checks.py`가 쪽 번호를 원본 기준으로 바꾸고 검산 불일치를 경고로 남긴다.
- `src/ai_worker/prompts/`: 추출 프롬프트·응답 스키마와 텍스트 입력용 앞뒤 문구. 모델·문구·쪽 입력을 바꾸면 대표 PDF로 정확도를 다시 확인한다.

스키마는 Spring Flyway가 관리한다. 워커는 `toadzip_ai_worker` 역할로 접속하고, 이 역할은 `ai_jobs`를 읽고 상태·결과 열만 고칠 수 있다.
역할은 [`infra/db/create-ai-worker-role.sql`](../infra/db/create-ai-worker-role.sql)로 만들고, 서버 배포는 [배포 운영의 AI 워커](../docs/operations/deployment.md#ai-워커)를 따른다.

## 실행

로컬 DB(`compose.local.yaml`)에 역할을 한 번 만든다.

```bash
docker compose -f compose.yaml -f compose.local.yaml exec -T db psql -U toadzip -d toadzip < infra/db/create-ai-worker-role.sql
docker compose -f compose.yaml -f compose.local.yaml exec db psql -U toadzip -d toadzip -c '\password toadzip_ai_worker'
```

공급표는 표 셀 구조로 코드가 묶고(`supply_grouping`), 수량 열의 뜻을 Jev로 확인한 경우에만 공급 단계 모델 호출을 생략한다(`jev`). `TYPESAFE_API_KEY`가 없거나 코드가 표 구조를 확신하지 못하면 공급표는 모델 단계로 처리한다.

컨테이너로 띄우려면 `.env`에 `AI_WORKER_DB_PASSWORD`·`OPENAI_API_KEY`(선택 `TYPESAFE_API_KEY`)를 넣고 `docker compose -f compose.yaml -f compose.local.yaml --profile ai-worker up -d ai-worker`를 실행한다. 직접 띄울 때는 다음과 같다.

```bash
cd ai-worker
PGHOST=localhost PGPORT=5433 PGDATABASE=toadzip PGUSER=toadzip_ai_worker PGPASSWORD=<비밀번호> OPENAI_API_KEY=<키> uv run python -m ai_worker
```

SIGTERM·SIGINT를 받으면 처리 중인 작업을 끝내고 멈춘다. 그 전에 강제로 끝나면 lease가 끝난 뒤 다른 워커가 그 작업을 다시 시도한다.

끝에 `--check`를 붙이면 작업을 꺼내지 않고 DB 접속과 `ai_jobs` 권한만 확인한 뒤 끝난다. 릴리스가 워커를 바꾸기 전에 이 확인을 실행한다.

## 테스트

Docker가 켜져 있어야 한다. 테스트는 OpenAI API를 부르지 않는다(`httpx.MockTransport`). 큐 테스트는 Postgres 17 컨테이너에 Flyway 마이그레이션 `V20261103_01__ai_jobs.sql`을 그대로 적용하고, 워커를 제한 역할로 접속시켜 확인한다.

```bash
cd ai-worker
uv sync
uv run pytest
```

PR에서는 [AI Worker CI](../.github/workflows/ai-worker-ci.yml)가 같은 테스트를 돌린다.

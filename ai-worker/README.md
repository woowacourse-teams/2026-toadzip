# AI 워커

Postgres `ai_jobs` 작업 큐를 처리하는 Python 워커다. Spring은 작업을 넣고 결과를 읽기만 하고, 꺼내기·lease·재시도·완료·실패와 PDF 비우기는 워커가 한다. 상태 전이 규칙과 Spring과 맞춘 JSON 형식은 [contract/README.md](contract/README.md)에 있다.

- `src/ai_worker/jobs.py`: `ai_jobs` SQL 전부. 연결은 autocommit이고 시각은 DB `now()`를 쓴다.
- `src/ai_worker/extraction.py`: 추출기 계약. 일시 오류(`TransientError`)는 시도가 남았으면 재시도하고, 그 밖의 오류는 바로 실패로 끝낸다.
- `src/ai_worker/worker.py`: `run_once`가 작업 하나를 꺼내 추출하고 기록한다. lease 10분, 재시도 간격 1분 → 5분.

스키마는 Spring Flyway가 관리한다. 워커는 `toadzip_ai_worker` 역할로 접속하고, 이 역할은 `ai_jobs`를 읽고 상태·결과 열만 고칠 수 있다.

## 테스트

Docker가 켜져 있어야 한다. 테스트는 Postgres 17 컨테이너에 Flyway 마이그레이션 `V20261103_01__ai_jobs.sql`을 그대로 적용하고, 워커를 제한 역할로 접속시켜 확인한다.

```bash
cd ai-worker
uv sync
uv run pytest
```

# 에이전트 역할 설정

저장소에서 사용하는 백엔드 탐색·설계 검토·리뷰 에이전트의 역할과 실행 설정을 관리합니다.

## 디렉터리·파일 구성

| 경로 | 역할 |
| --- | --- |
| [agents/](agents/) | 에이전트의 이름, 역할, 추론 수준과 읽기 전용 권한을 정의한 TOML 파일을 담습니다. |
| [agents/backend_explorer.toml](agents/backend_explorer.toml) | 실행 흐름과 변경 영향 범위를 조사합니다. |
| [agents/backend_architect.toml](agents/backend_architect.toml) | 계층, API, 트랜잭션과 데이터 경계를 검토합니다. |
| [agents/backend_reviewer.toml](agents/backend_reviewer.toml) | 변경 사항의 정확성, 회귀, 보안과 테스트 누락을 리뷰합니다. |

공통 작업 지침은 [AGENTS.md](../AGENTS.md), 백엔드 협업 원칙은
[agent-collaboration.md](../backend/docs/agent-collaboration.md)를 따릅니다.

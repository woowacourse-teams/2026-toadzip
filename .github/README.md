# GitHub 협업 설정

이슈·PR 작성 양식과 GitHub Actions 자동 검사를 관리합니다.

## 디렉터리·파일 구성

| 경로 | 역할 |
| --- | --- |
| [ISSUE_TEMPLATE/](ISSUE_TEMPLATE/) | 기능, 버그, 문서, 리팩터링과 유지 보수 작업의 이슈 양식을 담습니다. |
| [PULL_REQUEST_TEMPLATE.md](PULL_REQUEST_TEMPLATE.md) | 관련 이슈, 작업 목적·내역, 리뷰 포인트와 검증 결과를 작성하는 PR 양식입니다. |
| [workflows/backend-ci.yml](workflows/backend-ci.yml) | PR 또는 다른 워크플로의 호출로 Flyway 마이그레이션 검사와 Testcontainers 기반 백엔드 검증을 실행합니다. |
| [workflows/frontend-ci.yml](workflows/frontend-ci.yml) | PR 또는 다른 워크플로의 호출로 알림 배포 설정과 프론트엔드 검증을 실행합니다. |
| [workflows/deploy-dev.yml](workflows/deploy-dev.yml) | `develop` 푸시 또는 수동 실행 시 두 CI가 통과하면 개발 서버의 Self-hosted Runner에서 배포합니다. |
| [workflows/deploy-prod.yml](workflows/deploy-prod.yml) | `main` 푸시 또는 수동 실행 시 두 CI가 통과하면 운영 서버의 Self-hosted Runner에서 배포합니다. |

실행 조건과 명령의 원본은 각 워크플로입니다. 수동 배포도 개발은 `develop`, 운영은 `main`에서만 배포 작업을 실행합니다.
이슈·브랜치·커밋·PR 규칙은 [기여 가이드](../CONTRIBUTING.md), 문서 분류는 [공용 문서 목차](../docs/README.md)를 따릅니다.

# GitHub 협업 설정

이슈·PR 작성 양식과 GitHub Actions 검증·배포 설정을 관리한다.

## 구성 안내

| 구성 | 프로젝트에서의 역할 |
|---|---|
| [ISSUE_TEMPLATE/](ISSUE_TEMPLATE/) | 이슈 작성 양식 |
| [PULL_REQUEST_TEMPLATE.md](PULL_REQUEST_TEMPLATE.md) | PR 작성 양식 |
| [ci-paths.yml](ci-paths.yml) | PR에서 백엔드·프론트엔드 검증이 필요한 변경 경로 |
| [backend-ci.yml](workflows/backend-ci.yml) | Flyway 검사와 백엔드 테스트 두 분할을 실행하고 `Backend Check`로 결과 취합 |
| [frontend-ci.yml](workflows/frontend-ci.yml) | 린트·테스트·빌드와 알림 배포 설정을 `Frontend Check`로 검증 |
| [deploy-dev.yml](workflows/deploy-dev.yml) · [deploy-prod.yml](workflows/deploy-prod.yml) | CI 통과 커밋을 고정하고 환경별 Self-hosted Runner에서 Blue/Green 순차 배포 |

## 관련 문서

- [기여 가이드](../CONTRIBUTING.md): 이슈·브랜치·커밋·PR 규칙
- [CI·배포 운영](../docs/operations/deployment.md): CI 실행 범위·배포 조건·실패 대응
- [백엔드 검증](../docs/development/backend/testing.md#실행-순서) · [프론트엔드 검증](../docs/development/frontend/testing.md): 로컬 검증 방법

# GitHub 협업 설정

이슈·PR 양식과 자동 검사를 관리한다. 작업 규칙은 [기여 가이드](../CONTRIBUTING.md)를 따른다.

| 경로 | 용도 |
|---|---|
| [ISSUE_TEMPLATE/](ISSUE_TEMPLATE/) | 기능·버그·문서·리팩터링·유지 보수 이슈 양식 |
| [PULL_REQUEST_TEMPLATE.md](PULL_REQUEST_TEMPLATE.md) | 변경 내용·리뷰 포인트·검증 결과 작성 |
| [backend-ci.yml](workflows/backend-ci.yml) | PostgreSQL을 사용하는 백엔드 `check` |
| [frontend-ci.yml](workflows/frontend-ci.yml) | 프론트엔드 `npm run check` |

두 CI는 PR과 `main`·`develop` 푸시에서 실행한다.

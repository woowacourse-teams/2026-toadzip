# GitHub 협업 설정

이슈·PR 작성 양식과 GitHub Actions 자동 검사를 관리합니다.

## 디렉터리·파일 구성

| 경로 | 역할 |
| --- | --- |
| [ISSUE_TEMPLATE/](ISSUE_TEMPLATE/) | 기능, 버그, 문서, 리팩터링과 유지 보수 작업의 이슈 양식을 담습니다. |
| [PULL_REQUEST_TEMPLATE.md](PULL_REQUEST_TEMPLATE.md) | 관련 이슈, 작업 목적·내역, 리뷰 포인트와 검증 결과를 작성하는 PR 양식입니다. |
| [workflows/](workflows/) | GitHub Actions 워크플로를 담습니다. |
| [workflows/harness-check.yml](workflows/harness-check.yml) | PR 및 `main`·`develop` 푸시에 저장소 규칙, PostgreSQL 백엔드, 프론트엔드와 백업 자동화를 검사합니다. |

이슈·브랜치·커밋·PR 규칙은 [기여 가이드](../CONTRIBUTING.md)를 따릅니다.

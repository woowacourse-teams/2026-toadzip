# GitHub 협업 설정

이슈·PR 작성 양식과 GitHub Actions 자동 검사를 관리합니다.

## 디렉터리·파일 구성

| 경로 | 역할 |
| --- | --- |
| [ISSUE_TEMPLATE/](ISSUE_TEMPLATE/) | 기능, 버그, 문서, 리팩터링과 유지 보수 작업의 이슈 양식을 담습니다. |
| [PULL_REQUEST_TEMPLATE.md](PULL_REQUEST_TEMPLATE.md) | 관련 이슈, 작업 목적·내역, 리뷰 포인트와 검증 결과를 작성하는 PR 양식입니다. |
| [workflows/](workflows/) | GitHub Actions 워크플로를 담습니다. |
| [ci-paths.yml](ci-paths.yml) | PR에서 백엔드·프론트엔드 검증을 실행할 변경 경로를 정의합니다. |
| [workflows/backend-ci.yml](workflows/backend-ci.yml) | Flyway 검사와 백엔드 테스트 두 분할을 실행하고 `Backend Check`로 결과를 모읍니다. |
| [workflows/frontend-ci.yml](workflows/frontend-ci.yml) | 프론트엔드 린트·테스트·빌드와 알림 배포 설정을 `Frontend Check`로 검사합니다. |

## CI 실행 범위

- PR은 `dorny/paths-filter`로 변경된 영역만 검증합니다. 설명 문서만 바뀌면 무거운 검증을 생략합니다.
  루트 설정, `.github/`, `scripts/`, `tests/`의 실행 파일과 공통 테스트 자료 `docs/fixtures/` 변경은 양쪽 검증을 실행합니다.
  백엔드의 지도 대표 좌표 CSV도 프론트엔드 일치 검사에서 읽으므로 양쪽을 검증합니다.
  `ci-paths.yml`의 제외 규칙은 `some-with-excludes` 방식으로 평가합니다. 소스 디렉터리 안의 Markdown 리소스는 제외하지 않습니다.
- 필수 검사 이름은 `Backend Check`와 `Frontend Check`를 유지합니다. 경로 감지가 실패하거나 필요한 테스트가 실패·취소되면 통과하지 않습니다.
  백엔드는 두 분할이 모두 성공해야 통과하며, 변경이 없어서 분할을 생략한 경우만 성공으로 처리합니다.
- `develop`·`main` 배포에서 재사용 워크플로를 호출하면 경로 필터 없이 전체를 검증합니다.
  호출하는 워크플로에도 `contents: read`, `pull-requests: read` 권한이 필요합니다.
- Gradle 캐시는 PR에서 읽기 전용이며, `develop`·`main`의 `application` 분할만 저장합니다. 두 분할 모두 전체 소스를 컴파일합니다.
  `Set up Gradle` 로그의 복원 결과와 `compileJava`·`compileTestJava`의 `FROM-CACHE`를 확인합니다. `test --rerun`으로 테스트는 매번 실행합니다.
- 백엔드 보고서는 `backend-test-reports-ingest`와 `backend-test-reports-application` 아티팩트에 각각 저장됩니다.
  로컬 분할 실행 방법은 [백엔드 테스트 전략](../backend/docs/testing.md)을 따릅니다.

이슈·브랜치·커밋·PR 규칙은 [기여 가이드](../CONTRIBUTING.md)를 따릅니다.

# Contributing

## 이슈 작성

기능 개발이나 버그 수정 등의 작업을 시작하기 전에 관련 GitHub 이슈를 생성한다.

## 브랜치 생성

| 브랜치 | 용도 | 배포 대상 |
| --- | --- | --- |
| `main` | 운영 배포가 가능한 릴리스 | 운영 서버 |
| `develop` | 통합 개발 작업 | 개발 서버 |
| `<type>/<issue-number>-<short-description>` | 개별 작업 | `develop`에 병합 |

최신 `develop`에서 작업 브랜치를 생성한다. 작업이 끝나면 PR을 통해 `develop`에 병합한다.

운영 배포 시에는 `develop`에서 `main`으로 PR을 생성하여 병합한다. `main`과 `develop`에는 직접 푸시하지 않으며, 병합이 끝난 작업 브랜치는 삭제한다.

### 브랜치 이름

작업 브랜치에는 작업 타입, GitHub 이슈 번호와 작업 내용을 사용한다.

```text
<type>/<issue-number>-<short-description>
```

예시:

```text
feat/12-kakao-login
fix/18-duplicate-address
docs/21-branch-convention
```

`<type>`은 커밋 메시지와 동일한 타입을 사용한다. `<issue-number>`에는 관련 GitHub 이슈 번호를 입력하고, `<short-description>`은 작업 목적을 나타내는 짧은 영문 kebab-case로 작성한다.

## 로컬 작업 자료

개인 메모, 작업 계획·진행 기록, 실험용 코드와 로컬 검증 자료는 저장소 루트의
`.local/`에 보관한다. 각자의 checkout에서 같은 상대 경로를 사용하며, 필요한
하위 폴더만 만든다. 다음은 용도별 예시다.

```text
.local/
├── notes/       # 개인 메모
├── plans/       # 작업 계획·진행 기록
├── prototypes/  # 개인 실험·시안
└── data/        # 로컬 검증 자료
```

`.local/`은 루트 `.gitignore`의 `/.local/` 규칙으로 Git 추적에서 제외한다.
기존 `docs/superpowers/plans/`와 `docs/superpowers/specs/`도 로컬 전용 경로로 유지한다.
이 경로의 파일은 절대 커밋하지 않으며, `.gitignore`를 우회하는 `git add -f`도
사용하지 않는다. 개인 자료의 이동·삭제는 소유자의 명시적인 요청이 있을 때만 한다.
자동 정리 작업도 `.local/`의 자료를 임의로 삭제하지 않는다.

팀이 공유해야 하는 최종 규칙·문서는 관련 서비스·개발 문서에 정리한다.
재현 가능한 테스트·도구와 정식 자산은 해당 프로젝트 경로에 저장하고 커밋한다.
환경 변수와 비밀값은 기존 `.env` 관리 규칙을 따르며, `.local/`을 비밀값 보관
규칙의 대체 수단으로 사용하지 않는다.

이미 추적 중인 파일은 `.gitignore` 추가만으로 추적이 해제되지 않는다.
관련 자료의 추적 해제를 요청받으면 로컬 파일을 보존하고 `git rm --cached`로
추적만 해제한다. 과거 커밋 이력은 재작성하지 않는다.

## 커밋 메시지

### 메시지 형식

AngularJS 컨벤션을 따른다.

```text
<타입>: <요약> (#이슈번호)
<타입>(<범위>): <요약> (#이슈번호)
```

범위는 선택 사항이며 변경 영역을 명확하게 나타낼 필요가 있을 때만 작성한다. 타입과 범위는 영문 식별자를 사용하고 요약은 한글로 통일한다. 요약은 `추가한다`, `수정한다`처럼 문장 종결 어미를 사용하지 않고 `추가`, `수정`, `분리`처럼 간결하게 작성한다. 커밋 제목 끝에는 관련 이슈 번호를 `(#이슈번호)` 형식으로 작성한다. 다음 타입 중 하나를 사용한다.

| 타입 | 사용 시점 |
| --- | --- |
| `feat` | 사용자에게 보이는 새 기능 |
| `fix` | 버그 수정 |
| `docs` | 문서만 변경 |
| `style` | 동작 변경 없는 포맷팅 |
| `refactor` | 동작 변경 없는 코드 구조 개선 |
| `test` | 테스트 |
| `build` | 빌드 시스템 또는 의존성 변경 |
| `ci` | CI/CD 설정 |
| `chore` | 유지 보수 작업 |

예시:

```text
feat(auth): 카카오 로그인 콜백 추가 (#12)
fix(residence): 중복 주소 검증 (#18)
docs: 팀 브랜치 컨벤션 추가 (#21)
```

## PR 작성

저장소의 PR 템플릿을 사용한다. 작업 ID, 주요 변경 사항, 참고 자료를 포함한다.

## 리뷰와 병합

PR은 1명 이상의 리뷰를 받은 뒤에만 병합하며, 작성자는 리뷰 피드백을 해결하고 관련 검증이 통과했는지 확인한다.

### 병합 커밋 메시지

PR을 병합할 때는 Create a merge commit을 사용한다. Merge commit 제목은 PR 제목을 그대로 사용하므로 PR 제목이 컨벤션에 맞는지 병합 전에 확인한다.

## 테스트 및 검증

PR을 생성하기 전에 변경 사항과 관련된 테스트를 실행한다. 백엔드 코드를 변경한 경우 다음 명령어로 전체 테스트를 실행한다.

```bash
docker compose --project-name toadzip-test --file compose.test.yaml \
  up --detach --wait --wait-timeout 60 --force-recreate db db-shared

cd backend
./gradlew --rerun-tasks check
cd ..

docker compose --project-name toadzip-test --file compose.test.yaml \
  down --volumes --remove-orphans
```

Gradle 검사가 실패해도 마지막 정리 명령을 실행한다.

기능이나 API 동작을 변경한 경우 관련 시나리오를 직접 확인한다. 실행한 테스트와 검증 결과는 PR 템플릿의 `검증 결과`에 작성한다. 검증하지 못한 항목이 있다면 그 사유를 함께 작성한다.

## 코드 컨벤션

백엔드 코드는 [Backend Code Convention](backend/CODE_CONVENTION.md)을 따른다.

# 기여 규칙

## Git 작업

### 이슈와 브랜치 생성

작업 전에 관련 GitHub 이슈를 만들고 최신 `develop`에서 작업 브랜치를 생성한다.

| 브랜치 | 용도 |
|---|---|
| `main` | 운영 릴리스 |
| `develop` | 개발 통합 |
| `<type>/<issue-number>-<short-description>` | 개별 작업. 예: `feat/12-kakao-login` |

작업은 PR로 `develop`에 병합하고, 운영 릴리스는 `develop` → `main` PR로 병합한다.
`main`·`develop`에 직접 푸시하지 않는다. 병합이 끝난 작업 브랜치는 삭제한다.
브랜치 타입은 아래 커밋 타입을 쓰고 설명은 영문 kebab-case로 작성한다.

### 커밋과 PR

```text
<타입>(<선택_범위>): <한글_요약> (#이슈번호)
```

AngularJS 컨벤션을 따른다. 타입·범위는 영문, 요약은 `추가한다` 대신 `추가`처럼 간결한 한글로 쓴다.
예: `feat(auth): 카카오 로그인 콜백 추가 (#12)`.

| 타입 | 변경 |
|---|---|
| `feat` | 사용자 기능 |
| `fix` | 버그 |
| `docs` | 문서 |
| `style` | 동작 변경 없는 포맷 |
| `refactor` | 동작 변경 없는 구조 |
| `test` | 테스트 |
| `build` | 빌드·의존성 |
| `ci` | CI/CD |
| `chore` | 유지보수 |

[PR 템플릿](.github/PULL_REQUEST_TEMPLATE.md)에 작업 ID·변경·참고 자료·검증 결과를 작성한다.
리뷰 1명 이상, 피드백 해결과 관련 검증 통과 후 Create a merge commit으로 병합한다.
병합 커밋 제목은 PR 제목을 사용하므로 PR 제목도 커밋 형식을 따른다.

## 로컬 작업 자료

개인 메모·계획·진행 기록·실험·로컬 검증 자료는 `.local/`에 둔다. 필요한 하위 폴더만 만든다.
`.local/`, `docs/superpowers/plans/`, `docs/superpowers/specs/`는 로컬 전용이다.
하위 파일을 커밋하거나 `git add -f`로 stage하지 않는다. 기존 개인 자료는 요청 없이 이동·삭제하지 않는다.

팀 공유 문서·재현 가능한 테스트·도구·정식 자산은 관련 프로젝트 경로에 둔다. 비밀값은 기존 `.env` 규칙을 따른다.
이미 추적된 개인 자료의 추적 해제를 요청받으면 로컬 파일을 보존하고 `git rm --cached`를 사용한다. 과거 이력은 재작성하지 않는다.

## 테스트 및 검증

PR 전에 변경과 관련된 테스트를 실행한다. 백엔드 코드 변경 시 다음 전체 검사를 실행한다.

```bash
docker compose --project-name toadzip-test --file compose.test.yaml \
  up --detach --wait --wait-timeout 60 --force-recreate db db-shared

cd backend
./gradlew --rerun-tasks check
cd ..

docker compose --project-name toadzip-test --file compose.test.yaml \
  down --volumes --remove-orphans
```

검사가 실패해도 PostgreSQL 정리 명령은 실행한다.
기능·API 변경은 관련 시나리오를 확인하고 실행 결과·미검증 항목·이유를 PR에 남긴다.

Java 규칙은 [코드 컨벤션](backend/CODE_CONVENTION.md), 검증 범위는 [테스트와 검증](backend/docs/quality-gates.md)을 따른다.

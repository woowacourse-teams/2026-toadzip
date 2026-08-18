# Git Conventions

## 커밋

AngularJS 형식을 따른다.

```text
<type>: <한글 요약> (#이슈번호)
<type>(<scope>): <한글 요약> (#이슈번호)
```

- `scope`는 변경 영역을 구분할 때만 쓰는 선택적 영문 식별자다.
- 요약은 한글로 쓰고 `추가`, `수정`, `분리`처럼 간결하게 끝낸다.
- `추가한다`, `수정한다` 같은 문장 종결형은 사용하지 않는다.

| type | 사용 시점 |
|---|---|
| `feat` | 사용자에게 보이는 새 기능 |
| `fix` | 버그 수정 |
| `docs` | 문서만 변경 |
| `style` | 동작 변경 없는 포맷팅 |
| `refactor` | 동작 변경 없는 코드 구조 개선 |
| `test` | 테스트 |
| `build` | 빌드 시스템 또는 의존성 변경 |
| `ci` | CI/CD 설정 |
| `chore` | 유지 보수 작업 |

```text
feat(auth): 카카오 로그인 콜백 추가 (#12)
fix(residence): 중복 주소 검증 (#18)
docs: 팀 브랜치 컨벤션 추가 (#21)
```

## 브랜치와 Git Flow

| 브랜치 | 용도 | 배포 대상 |
|---|---|---|
| `main` | 운영 배포 가능한 릴리스 | 운영 서버 |
| `dev` | 통합 개발 작업 | 개발 서버 |
| `<type>/<issue>-<description>` | 개별 작업 | `dev`에 병합 |

1. 관련 이슈를 먼저 만든다.
2. 최신 `dev`에서 작업 브랜치를 만든다.
3. `<type>/<issue-number>-<english-kebab-case>`로 이름을 짓는다.
4. PR로 `dev`에 병합하고 작업 브랜치를 삭제한다.
5. 운영 배포는 `dev`에서 `main`으로 PR을 만들어 병합한다.

`main`과 `dev`에는 직접 push하지 않는다. GitHub Branch protection에서 두 브랜치의
PR 필수와 Harness Check 필수를 설정한다.

```text
feat/12-kakao-login
fix/18-duplicate-address
docs/21-branch-convention
```

## 자동 검사

- `commit-msg` 훅은 커밋 제목 형식과 문장 종결형을 검사한다.
- CI는 작업 브랜치→`dev`, `dev`→`main` 방향과 PR 제목을 검사한다.

# 저장소 에이전트 지도

## 공통 맥락

- 모든 작업 전에 [SERVICE_OVERVIEW.md](SERVICE_OVERVIEW.md)에서 서비스 목적과 핵심 용어를 확인한다.
- 기존 사용자 변경을 보존하고 요청 범위 밖의 파일을 수정하지 않는다.
- `docs/superpowers/plans/`와 `docs/superpowers/specs/`는 로컬 작업 자료다. 하위 파일을 절대 커밋하거나 강제로 stage하지 않는다. 스킬의 계획·설계 커밋 지침보다 이 규칙을 우선한다.
- 파괴적 작업, 외부 쓰기, 비밀 접근은 권한과 정확한 대상을 먼저 확인한다.

## 작업별 지침

- 백엔드 작업은 [backend/AGENTS.md](backend/AGENTS.md)를 추가로 따른다.
- 백엔드 코드 컨벤션의 원본은 [backend/CODE_CONVENTION.md](backend/CODE_CONVENTION.md)다.
- 프론트엔드 작업은 [frontend/AGENTS.md](frontend/AGENTS.md)를 추가로 따른다.
- 저장소 기여와 Git 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따른다.

## 저장소 공통 하네스

- Codex 역할은 `.codex/agents/`에 둔다.
- Git hook은 `.githooks/`, 자동 검사는 `scripts/`와 `tests/harness/`에 둔다.
- GitHub 검증은 `.github/workflows/harness-check.yml`에서 실행한다.
- 로컬 계획·설계의 Git 추적은 `scripts/validate-local-artifacts.sh`로 커밋 전과 CI에서 차단한다.

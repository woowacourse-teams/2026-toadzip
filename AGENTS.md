# 저장소 에이전트 지도

## 공통 맥락

- 모든 작업 전에 [SERVICE_OVERVIEW.md](SERVICE_OVERVIEW.md)에서 서비스 목적과 핵심 용어를 확인한다.
- 기존 사용자 변경을 보존하고 요청 범위 밖의 파일을 수정하지 않는다.
- 개인 메모, 작업 계획·진행 기록, 실험용 코드와 로컬 검증 자료는 저장소 루트의 `.local/`에 보관한다. 필요한 하위 폴더만 만들고, 팀 공유 문서와 재현 가능한 테스트·도구는 관련 프로젝트 경로에 둔다.
- `.local/`, `docs/superpowers/plans/`와 `docs/superpowers/specs/`는 로컬 작업 자료다. 하위 파일을 절대 커밋하거나 강제로 stage하지 않는다. 스킬의 계획·설계 커밋 지침보다 이 규칙을 우선한다.
- 기존 개인 자료를 임의로 이동하거나 삭제하지 않는다. 자동 정리 작업도 `.local/`의 자료를 임의로 삭제하지 않는다.
- 파괴적 작업, 외부 쓰기, 비밀 접근은 권한과 정확한 대상을 먼저 확인한다.

## 작업별 지침

- 백엔드 작업은 [backend/AGENTS.md](backend/AGENTS.md)를 추가로 따른다.
- 백엔드 코드 컨벤션의 원본은 [backend/CODE_CONVENTION.md](backend/CODE_CONVENTION.md)다.
- 프론트엔드 작업은 [frontend/AGENTS.md](frontend/AGENTS.md)를 추가로 따른다.
- 저장소 기여와 Git 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따른다.

## 저장소 공통 설정

- Codex 역할은 `.codex/agents/`에 둔다.
- GitHub 검증은 `.github/workflows/`의 백엔드·프론트엔드 CI에서 실행한다.

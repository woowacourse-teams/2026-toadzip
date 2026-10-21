# 프론트엔드 에이전트 지도

루트 [AGENTS.md](../AGENTS.md)의 공통 지침에 더해 `frontend/` 작업에 적용한다.

## 필수 개발 기준

- 설치·실행은 [README.md](README.md), 기술 역할·구현 경계는 [프론트엔드 개발 기준](../docs/development/frontend/standards.md)을 따른다.
- 변경할 코드, 가장 가까운 테스트와 실제 설정 파일을 함께 읽는다. 바뀌기 쉬운 버전·명령은 문서에 복제하지 않고 설정 원본에서 확인한다.
- UI·스타일은 [디자인 시스템](../docs/development/frontend/design-system.md)의 기존 토큰·컴포넌트와 실제 사용처를 먼저 확인한다. 디자인 기준은 사용자용 공고·단지 검색 화면이며 관리자 페이지 디자인은 참고하지 않는다.
- 동작 변경에는 가장 가까운 행동 테스트를 두고, 완료 전 [프론트엔드 검증 기준](../docs/development/frontend/testing.md)의 자동 검사·브라우저 확인·보고 기준을 따른다.
- 화면별 정책과 외부 연동 계약은 [공용 문서 목차](../docs/README.md)에서 찾는다.

## 외부 경계와 검증

- TypeScript·린트·빌드 변경, API·브라우저·SDK·환경변수 변경은 [개발 기준의 설정 원본과 외부 경계](../docs/development/frontend/standards.md)를 확인한다. 의존성 추가·교체는 같은 문서의 도입 조건과 현재 lock 파일을 확인한다.
- 브라우저에 비밀값을 넣지 않는다.
- jsdom 테스트나 빌드 성공을 실제 브라우저·외부 SDK 동작의 증거로 표현하지 않는다.

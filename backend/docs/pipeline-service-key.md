# 수집 실행의 일회성 서비스키

관리자의 수동 수집 시작 요청은 선택 JSON 본문 `{ "serviceKey": "..." }`를 받는다.
경로는 `POST /api/admin/ingest/pipelines/{type}`이며 `complex-collection`, `announcement-collection`,
`complex-sync`, `announcement-sync`가 입력키를 받는다. 통합 실행은 같은 실행 범위에서 수집 후 정제를 수행한다.
본문을 보내지 않으면 기존 서버 설정키를 사용한다.

- 본문이 있으면 `serviceKey`가 필수이며 공백만 있는 값과 4096자를 넘는 값은 HTTP 400으로 거부한다.
- 입력키의 양끝 공백을 제거한다. 원문 키와 URL 인코딩된 키를 모두 지원한다.
- 정제 시작에 키 본문을 보내면 HTTP 400 `INVALID_INGEST_REQUEST`로 거부한다.
- 기존 관리자 인증과 CSRF 검사를 그대로 적용한다.

입력키는 해당 비동기 수집 실행과 그 병렬 작업에만 전달한다.
실행별 scope는 닫힐 때 이전 스레드 상태를 복원하고 키가 없던 스레드에서는 제거한다.
입력키를 실행 Entity, 응답 DTO, 실행 로그 맥락이나 요청 조건에 저장하지 않는다.
외부 오류 메시지에 반영된 키를 가리고 요청 URL을 포함할 수 있는 원본 예외는 전파하지 않는다.
입력 DTO의 `toString()`도 키 값을 숨긴다.

브라우저 입력키를 다음 실행에 재사용하지 않는다.

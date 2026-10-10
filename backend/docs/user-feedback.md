# 사용자 의견 접수

로그인 없이 의견을 제출하고 관리자가 검색·조회한다.

## 의견 제출

- `POST /api/v1/feedback`: 로그인 없이 `{ "content": "의견 내용" }` 제출
- `GET /api/auth/csrf`에서 받은 토큰을 요청 헤더에 전달한다. 기존 CSRF 보호를 유지한다.
- 내용은 필수이며 UTF-16 기준 최대 2,000자다. 양끝 공백을 제거하고 내부 줄바꿈은 보존한다.
- NBSP·좁은 NBSP·BOM을 포함한 양끝 공백도 제거하며 공백만 담긴 의견은 거절한다.
- 성공은 `201`과 `{ "data": { "id": 1 } }`, 검증 실패는 기존 `VALIDATION_FAILED` 오류 계약이다.
- 본문과 UTC 접수 시각만 저장한다. 회원 ID, 이메일, IP, 화면 URL을 별도로 수집하지 않는다.
- 공개 조회 API는 제공하지 않는다. 화면에서는 개인정보 입력을 피하도록 안내한다.

## 배포와 복구

- `V20261007_01__create_feedback.sql`은 새 테이블과 최신순 조회 인덱스만 추가한다.
- Flyway 적용 후 Hibernate validate와 애플리케이션 시작이 진행된다.
- 기존 테이블·데이터는 변경하지 않는다. 이전 애플리케이션으로 되돌려도 새 테이블을 보존한다.

## 관리자 조회

- `GET /api/admin/feedback?keyword=검색&page=0&size=20`은 ADMIN 세션만 접근할 수 있다.
- 검색어는 최대 200자, page는 0 이상, size는 1~100이며 기본값은 20이다.
- 본문을 대소문자 구분 없이 검색한다. `%`, `_`, `!`는 와일드카드가 아닌 입력 문자로 취급한다.
- `createdAt DESC, id DESC`로 정렬한다. `createdAt`은 UTC ISO 8601 시각이며 관리자 화면은 한국 시각으로 표시한다.
- 응답은 `data` 안에 `items`, `page`, `hasNext`, `totalElements`, `totalPages`를 포함한다.
- 각 항목은 `id`, 전체 `content`, `createdAt`을 포함한다. 범위 밖 페이지도 전체 검색 건수를 보존한다.
- 검색 건수를 먼저 확인하므로 매우 큰 범위 밖 페이지도 JPA offset 제한에 걸리지 않고 빈 목록을 반환한다.

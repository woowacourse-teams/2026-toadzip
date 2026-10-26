# 회원 북마크

단지·공고 북마크는 알림 설정과 독립적으로 저장한다. 인증된 회원의 ID만 사용한다.

| API | 동작 |
| --- | --- |
| `GET /api/v1/bookmarks/me` | 본인 목록, 최근 저장순 |
| `PUT /api/v1/bookmarks/me/{type}/{targetId}` | 멱등 저장, 204 |
| `DELETE /api/v1/bookmarks/me/{type}/{targetId}` | 본인 대상의 모든 중복을 해제, 204 |

`type`은 `COMPLEX`, `ANNOUNCEMENT`, `targetId`는 양의 Long이다.
회원 권한과 쓰기 요청의 CSRF 토큰이 필요하다. 없는·관리자가 삭제한 원본 저장은 404다.

GET 응답은 `{items: [...]}`이며 각 항목은 다음 필드를 가진다.

- `type`, 문자열 `targetId`, `name`, `createdAt`, `available`
- nullable `address`, `status`, `applicationStartDate`, `applicationEndDate`

`createdAt`은 기존 favorite 테이블의 로컬 날짜·시간(ISO 8601)이다.
공고 상태와 표시 일정은 기존 `ApplicationScheduleSql`과 서울 기준 오늘 날짜를 사용한다.
종료 공고를 제거하지 않는다. 관리자 삭제 항목은 `available=false`로 유지하고 해제할 수 있다.
원본 FK가 있으므로 물리 삭제된 원본을 별도 snapshot으로 보관하지 않는다.

기존 단지 병합·복원은 favorite 행을 그대로 이동한다. 새 unique 제약을 추가하면
양쪽 단지를 저장한 회원의 병합·복원이 깨지므로 기존 스키마를 유지한다.
대신 저장·해제 트랜잭션이 `users` 행을 잠가 동일 회원의 API 쓰기를 직렬화하고
`INSERT ... WHERE NOT EXISTS`로 중복을 방지한다. 병합에서 생긴 중복은 목록에서 하나로
보여주고 해제할 때 전부 삭제한다. 직접 DB에 쓰는 다른 경로는 이 API 잠금을 우회한다.

프론트 주소는 `/mypage`, `/mypage/bookmarks`, `/mypage/notifications`, `/mypage/account`, `/mypage/info`다.
`/notifications`는 받은 알림 보관함으로 유지하며 기존 알림·기기 설정을 공유한다. 지도 복귀 URL은 탭의 sessionStorage에
비민감 탐색 조건만 저장한다. OAuth 목적지는 허용된 내부 경로만 저장하고 30분 뒤 만료한다.
회원 북마크를 localStorage에 저장하지 않는다.

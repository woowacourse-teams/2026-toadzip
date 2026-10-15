# 관리자 회원 알림 신청 관리

/admin/notification-subscriptions는 회원 ID·로그인 이메일로 신청을 검색하고 대상 종류·대상 ID·상태로 조회한다.
이메일 검색어는 화면 메모리에만 두며 URL과 분석 SDK에 저장하지 않는다.
회원 ID에서 회원 상세로 이동하면 해당 회원의 신청 목록을 조회한다.
지역은 기존 지역 코드의 이름을 표시하고 단지·공고는 기존 관리자 상세로 연결한다.

## 조회 API

- GET /api/admin/notification-subscriptions: keyword(최대 200자), 선택적 userId, targetType, targetId, status, page, size.
- GET /api/admin/users/{userId}/notification-subscriptions: 회원별 status, page, size. 없는 회원은 404.
- GET /api/admin/notification-subscriptions/targets: 활성 신청 회원 순위. targetType, page, size로 조회한다.
- GET /api/admin/notification-subscriptions/trend: days=1~90(기본 30). 화면은 최근 7/30/90일을 제공한다.
- 일반 목록은 ACTIVE, 회원 상세는 ALL이 기본이다. 상태는 ALL/ACTIVE/CANCELLED/EXPIRED.
- page는 0부터, size는 기본 20·최대 100이다. 신청일 내림차순, 같은 시각은 구독 ID 내림차순, 신청일 미확인은 뒤에 표시한다.
- ADMIN 역할을 요구하며 개인정보 응답은 Cache-Control: no-store다. 새 ID 응답은 문자열로 제공한다.

응답은 기존 관리자 ApiResponse.data 아래 items/page/hasNext/totalElements/totalPages/summary를 제공한다.
summary는 activeMembers(회원 ID 중복 제거), activeSubscriptions(활성 설정 행 수), asOf(동일 DB 집계 기준시각)다.
일반 목록의 요약은 검색·대상·상태 필터와 무관한 전체 활성 신청이다.
회원별 요약은 해당 회원만 집계한다. 필터된 목록의 건수는 totalElements로 구별한다.
목록·전체건수·활성요약은 하나의 SQL 스냅샷과 기준시각으로 조회한다.

## 상태와 데이터 경계

- active=false는 CANCELLED, active=true이며 expires_at이 기준시각 이하이면 EXPIRED, 나머지는 ACTIVE다.
- 신청일은 privacy_notification_notices.requested_at만 사용한다. 없으면 미확인이고 updated_at으로 추정하지 않는다.
- 회원 이메일이나 고지 메타가 없는 신청도 조회한다. 같은 이메일의 다른 회원 ID는 합치지 않는다.
- 삭제·미확인 대상은 신청 행·대상 ID를 보존하고 링크 없이 대상 확인 불가로 표시한다.
- 조회는 설정·고지·수정 순서·업무 영수증을 변경하지 않는다. 회원용 current()의 잠금·revision 생성도 호출하지 않는다.
- 이 화면은 신청 설정 조회다. 알림 발송, 강제 해제, 회원 삭제·정지와 분석 행동 기록 연결을 제공하지 않는다.

비회원 알림 제거의 DB 마이그레이션·복구 절차는 [회원 알림 보유와 해제](../../docs/notification-retention-and-cancellation.md)를 따른다.

## 대상별 현황과 확인 필요

대상별 현황은 취소·만료를 제외하고 같은 대상의 회원 ID를 중복 제거해 집계한다.
같은 단지·공고의 선행 0 ID와 지역의 이전 코드는 하나의 대상으로 모은다.
순서는 활성 회원 수 내림차순, 대상 종류·통합 ID 오름차순이다.
대상을 누르면 groupTarget=true로 신청자 목록을 연다. 이때 이전 코드·선행 0 신청도 함께 조회한다.
일반 대상 ID 검색은 기존대로 원문 ID를 정확히 비교한다.

목록의 review 조건은 ALL/ANY/TARGET_UNAVAILABLE/REQUEST_DATE_UNKNOWN이다.
ANY는 대상 확인 불가 또는 신청일 기록 없음이며, 확인 필요 화면은 전체 상태로 시작한다.
지역은 현재 카탈로그와 등록된 이전 코드, 단지·공고는 존재 여부와 관리자 삭제 상태로 판정한다.
이메일 미제공은 정상이다. 과거 신청일 기록 부재도 잘못된 신청의 증거로 취급하지 않는다.

## 기간별 신청·취소 집계

V20261014_01은 개인 식별자 없는 notification_daily_activity를 만들고 집계 시작 시각을 기록한다.
회원·이메일·대상·요청 ID와 외래 키를 저장하지 않으며 과거 신청·영수증·분석 이벤트를 소급 사용하지 않는다.
NotificationSettingsService는 실제 ACTIVATED/CANCELLED 결과만 설정 저장과 같은 트랜잭션에서 더한다.
새 신청 및 취소·만료 후 재신청은 신청 1건, 실제 활성 신청 해제는 취소 1건이다.
같은 요청 재전송, ALREADY_ACTIVE/UNCHANGED, 실패·롤백과 자동 만료는 집계하지 않는다.
따라서 일별 신청·취소 차이는 현재 활성 신청 수의 증감과 동일하지 않다.

날짜는 Asia/Seoul 기준이다. 집계 시작 전은 null(미집계), 시작 후 변화가 없는 날은 0이다.
시작일과 오늘은 일부 시간만 포함한다. 화면은 조회한 기간 중 집계된 날짜의 건수만 합산한다.
회원 삭제나 고지·영수증 파기 후에도 개인 식별자가 없는 운영 합계는 회원에게 연결되지 않는다.
별도 집계 보유·정리는 [운영 정책](../../docs/privacy-policy.md)의 기준월 말일부터 최대 12개월과 담당자 확인 절차를 따른다.
기간 밖 집계를 정리할 때는 최초 집계 시각을 당일 기준행의 first_recorded_at에 먼저 보존한다.
당일 행이 없으면 0건으로 만들고, 기존 당일 건수는 유지한 뒤 기간 밖 행만 정리한다.
이 시각 메타가 있어야 집계 시작 이후 변화가 없던 날을 미집계로 잘못 표시하지 않는다.
배포는 additive migration이며 이전 앱으로 돌아가도 회원 설정과 신규 집계 테이블을 삭제하지 않는다.

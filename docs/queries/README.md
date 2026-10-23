# 운영 점검 SQL

알림 데이터·전달 상태·보유 및 스키마를 확인하는 읽기 전용 쿼리다. 대상 DB와 적용된 스키마를 확인하고 각 파일의 입력·실행 조건을 따른다.

## 자료 안내

| 자료 | 용도·조건 |
|---|---|
| [기존 이메일 대상](active-notification-recipients.sql) | target_type·target_id로 기존 이메일 설정을 조회. 전체 회원 신청자 목록이나 발송 권한이 아님 |
| [전달 상태](notification-delivery-status.sql) | 아웃박스·전달 상태·실패 코드·만료 lease와 참조 확인 |
| [관심·신청 지표](notification-interest-metrics.sql) | 노출·클릭과 긍정 응답, 현재 회원 설정을 구분해 집계 |
| [보유 상태](notification-retention-status.sql) | 오래된 기존 이벤트와 유효 신청이 없는 이메일 자료 확인 |
| [스키마 상태](notification-schema-status.sql) | 접속 대상·Flyway 이력·필수 스키마 확인. psql의 실행 전제를 파일에서 확인 |

## 관련 문서

실행·결과 해석은 [알림 운영](../operations/notifications.md)을 따른다. 조회 결과의 이메일 등 개인정보를 로그·PR·문서에 복사하지 않는다. 조회는 발송·삭제·복구 실행 승인을 대신하지 않는다.

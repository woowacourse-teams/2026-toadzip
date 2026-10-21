# 알림 신청 데이터와 스키마 점검

## 현재 계약과 점검 범위

현재 신청·조회는 [회원 알림 설정 API](../features/notifications/settings.md)를 따른다.
회원 ID와 선택 대상을 저장하며 이메일을 입력받지 않는다. 이메일 알림은 발송하지 않는다.
새 공고의 대상 매칭·알림함·FCM 전달은 [알림 생성·전달 계약](../features/notifications/delivery.md)을 따른다.
구현이 존재하는 것과 실제 서버의 활성화·전달 성공은 구분한다. 회원 이메일 수집·저장과 관리자 조회·검색 목적은 유지한다.

기존 유효 구독은 새 고지 메타가 없어도 조회·취소할 수 있다. 과거 행동 이벤트로 신청이나 고지 확인을
추정하지 않으며, 고지 메타가 없다는 이유로 재신청을 요구하지 않는다. 신규 개인정보 자료의
[저장 경계](../features/privacy/storage.md)와 [보유·파기 정책](privacy.md)은 별도 문서를 따른다.

이 문서의 조회 SQL은 **기존 자료 점검용**이다. 결과에는 개인정보가 있으므로 승인된 담당자만
조회하고 공개 로그·저장소에 남기지 않는다.

| 점검 도구 | 포함하는 자료와 한계 |
| --- | --- |
| [유효 신청 이메일 조회](../queries/active-notification-recipients.sql) | 기존 회원 알림 이메일 설정을 조회한다. `users.email`은 조회하지 않으므로 이메일 입력 없이 신청한 회원이 빠질 수 있다. 현재 전체 신청 목록이나 발송 허용 목록으로 해석하지 않는다. |
| [알림 지표 조회](../queries/notification-interest-metrics.sql) | 행동 지표는 기존 `notification_interest_events`만 읽으며 새 `privacy_notification_events`·업무 영수증을 포함하지 않는다. 구독 집계는 기존 구독 테이블의 현재 회원 신청을 포함한다. 최근 90일 필터는 조회 범위이며 자동 파기를 뜻하지 않는다. |

이메일 조회는 필요한 테이블의 `SELECT` 권한만 가진 계정으로 대상을 한정해 실행한다.
실제 발송 여부와 처리 근거는 별도 운영 판단이며 조회 결과만으로 발송하지 않는다.

```sh
psql -X -v ON_ERROR_STOP=1 -v target_type=REGION -v target_id=<지역_ID> \
  -f docs/queries/active-notification-recipients.sql
```

## 기존 자료의 저장 구조와 이력

다음은 기존 알림 테이블이 만들어진 이력이다. 새 개인정보 기록의 저장 위치는
[모듈 분리 계약](../features/privacy/storage.md)의 테이블 목록을 따른다.

| 마이그레이션 | 변화 |
| --- | --- |
| `V20260930_02` | 노출, 클릭, 신청, 창 닫기 이벤트를 누적하는 `notification_interest_events` 생성 |
| `V20260930_03` | `users.email`, 이벤트의 `email` 컬럼 추가 |
| `V20260930_04` | 이벤트의 `CANCELLED` 값 허용 |
| `V20260930_05` | 로그인 계정의 이메일 설정과 대상별 신청 상태 테이블 추가 |
| `V20260930_06` | 비로그인 브라우저의 이메일 설정과 대상별 신청 상태 테이블 추가 |
| `V20260930_07` | 신청의 12개월 만료일 추가, 이벤트의 이메일 열 제거 |
| `V20260930_08` | 비로그인 취소 요청·확인 코드 해시 테이블 추가 |
| `V20260930_09` | 수동 확인 코드 발송 시각과 관리자 계정 열 추가 |
| `V20261014_02` | 임시 비회원 알림 구독·이메일·취소 요청 테이블 제거 |

회원 신청은 대상별 `active`와 `expires_at`을 기존 구독 테이블에 저장한다.
업무 만료일을 신규 고지 메타에 복사하는 규칙은 [회원 알림 계약](../features/notifications/settings.md)을 따른다.
비회원 알림 조회·취소 기능과 전용 테이블은 V20261014_02에서 제거한다. 과거 적용 마이그레이션은 이력 검증을 위해 유지한다.

회원의 마지막 신청 취소 때 정리될 수 있는 자료도 기존 알림 이메일 설정이다. `users.email`은 유지한다.

기존 이벤트·구독·이메일 자료에 신규 파기 기간을 소급 적용하지 않는다. 기존 자료를 90일 뒤
자동 삭제한다고 안내하지 않으며, 별도 처리 근거·삭제 절차는 [운영 확인 항목](privacy.md)에 남긴다.
DB 백업 자동화는 저장소에서 제공하지 않으므로 제공자 수명주기와 복구 설정도 별도로 확인한다.

기존 알림 테이블은 회원·지역·단지·공고와 같은 PRIMARY PostgreSQL DB에 있다.
기존 업무의 FK와 단일 트랜잭션을 유지하되, 새 개인정보 테이블에는 기존 테이블을 가리키는 FK를 만들지 않는다.
SHARED DB는 이 기능의 대상이 아니다.

## 개발·운영 DB 확인

`develop` 병합은 개발 서버의 코드 배포 대상, `main` 병합은 운영 서버의 코드 배포 대상이다.
병합만으로 DB는 바뀌지 않는다. 각 서버에서 새 백엔드를 기동하면 해당 서버의 `.env`가 지정한
`PRIMARY_DB_HOST`, `PRIMARY_DB_PORT`, `PRIMARY_DB_NAME`의 주 DB에 Flyway가 적용된다.
개발과 운영의 DB 접속값은 별도로 관리하며 데이터가 서로 복사되지 않는다.

배포 전에는 **각 환경의 실제 주 DB**를 백업하고 복제본에서 앱 기동을 시험한다. 특히 Flyway 이력이
없는 기존 DB라면 스키마와 `uk_users_login_identifier` 제약을 [도입 절차](database/flyway.md)와
대조한다. 개인정보 기능이 포함된 앱은 이력이 없는 기존 DB의 자동 기준선·보정을 차단한다.
기존 서비스의 최초 Flyway 도입은 백업·격리 검증과 별도 승인 절차로 먼저 완료한다.
이미 정상 이력이 있으면 미적용 `V`만 실행한다. 빈 DB에는 `B20260922_01`과 후속 `V`가 적용된다.
개인정보 메타는 [별도 전용 테이블](database/privacy-deployment.md)에 저장하며 이 문서의
기존 알림 테이블·자료를 신규 개인정보 배치로 변경하거나 파기하지 않는다.

운영자는 새 백엔드 기동 후 다음 점검 스크립트를 실행하고 **종료 코드가 0일 때만**
알림 스키마 적용 완료로 기록한다. 개발과 운영에서 각각 해당 환경의 주 DB 연결 변수를 설정한다.
비밀번호는 명령이나 저장소에 적지 않고 서버의 접근 통제된 `PGPASSFILE` 등을 사용한다.

```shell
PGHOST=<개발_주_DB_주소> PGPORT=5432 PGDATABASE=toadzip PGUSER=<점검_계정> \
  PGPASSFILE=<보호된_개발_비밀번호_파일> sh scripts/check-notification-schema.sh dev

PGHOST=<운영_주_DB_주소> PGPORT=5432 PGDATABASE=toadzip PGUSER=<점검_계정> \
  PGPASSFILE=<보호된_운영_비밀번호_파일> sh scripts/check-notification-schema.sh prod
```

이 스크립트는 실제 DB 이름, 기존 Flyway 성공 이력과 알림 테이블·컬럼·제약을 읽기 전용으로 검사한다.
신규 개인정보 테이블과 독립 이력은 [개인정보 배포 검사](database/privacy-deployment.md)로 별도 확인한다.
하나라도 빠지면 오류와 함께 비정상 종료한다. 현재 배포 워크플로는 이 스크립트를 호출하지 않으므로
CI·healthcheck 성공만으로 이 점검의 완료를 대신하지 않는다. 별도 실행 결과를 기록한다.
출력의 DB 주소·이름·시각은 배포 기록에 남기고 의도한 환경과 대조한다. 원인 조사에는
[상세 조회 쿼리](../queries/notification-schema-status.sql)를 사용한다. 개발·운영 자동 배포는
[배포 운영](deployment.md)에 설명되어 있다. 실제 DB 접속 정보·스키마 점검 결과는 저장소에 없으므로
워크플로 파일의 존재나 CI 성공만으로 원격 DB의 점검 완료를 주장할 수 없다.

## 회원 알림 설정 보유와 해제

현재 알림 신청·조회·해제는 로그인한 회원만 제공한다.
회원은 보관함의 알림 설정 또는 /mypage/notifications에서 개별·전체 해제를 요청한다.
신청·만료·고지 기록의 원본 계약은 [회원 알림 설정](../features/notifications/settings.md)을 따른다.
회원 로그인 이메일은 관리자 회원 조회·검색용이며 알림 해제로 삭제하지 않는다.

### 비회원 알림 제거

V20261014_02는 임시 비회원 구독·이메일·취소 요청 테이블 3개를 삭제한다.
사용자 /notifications/cancel, 관리자 /admin/notification-cancellations와 관련 조회·취소 API도 제거한다.
기존 회원 설정·이메일·개인정보 메타·분석 선택은 유지한다.
이미 적용된 과거 Flyway 마이그레이션과 고지 원문은 이력으로 보존한다.

### 배포와 복구

배포 전에 적용 대상 PRIMARY DB와 백업을 확인하고 격리 복제본에서 마이그레이션을 검증한다.
구버전 서버는 제거된 테이블을 참조하므로 새 버전과 혼용하지 않는다.
구버전으로 앱만 롤백해도 비회원 기능은 복구되지 않는다. 필요하면 승인된 백업 복구 절차를 따른다.
개발 작업은 실제 운영 DB 삭제를 실행하지 않는다.
적용 후 [스키마 점검](../../scripts/check-notification-schema.sql)에서 새 마이그레이션의 성공과 비회원 테이블 부재를 확인한다.

## 설정과 출시 순서

기본값은 모두 false다. Firebase 비공개 키는 저장소나 VITE 변수에 넣지 않는다.

| 환경 변수 | 용도 |
|---|---|
| NOTIFICATION_PUBLISHER_ENABLED | 공고 저장 시 이벤트 기록 |
| NOTIFICATION_MATERIALIZER_ENABLED | 보관함 생성 |
| NOTIFICATION_DELIVERY_ENABLED | FCM 전송, true일 때만 ADC 로드 |
| NOTIFICATION_MAINTENANCE_ENABLED | 보관 정리와 대기열 지표 |
| NOTIFICATION_ACTIVATION_AT | 배포 후에도 바꾸지 않는 UTC 활성화 기준 |
| NOTIFICATION_FRONTEND_ORIGIN | 경로 없는 HTTPS 프론트 origin |
| NOTIFICATION_FIREBASE_PROJECT_ID | 전용 Firebase project ID |
| GOOGLE_APPLICATION_CREDENTIALS | 서버 ADC 자격 경로(워크로드 ID 사용 시 생략 가능) |

1. additive migration과 모든 플래그 off로 배포한다. 모든 서버가 새 `activated_at` 구독 코드를 사용해야 한다.
2. 활성화 시각을 한 번 고정한 뒤 publisher·materializer·maintenance를 켠다. 예전 공고 소급 발송은 하지 않는다.
3. 승인된 개발 Firebase 프로젝트와 테스트 계정·기기에서만 delivery를 검증한다.
4. 전경/배경/페이지 종료, 클릭 복귀, 권한 거부, 기기 2대, A 로그아웃→B 로그인, worker 갱신과 iPhone 홈 화면 설치를 확인한다.
5. 검증 완료 후 `VITE_NOTIFICATION_PUSH_ENABLED=true`로 빌드해 푸시 설정을 공개한다.

Docker 배포는 루트 `.env`의 `NOTIFICATION_*`를 backend 런타임에, `VITE_NOTIFICATION_*`·`VITE_FIREBASE_*`를
frontend 빌드 인자로 전달한다. 공개 웹 설정을 바꾸면 frontend 이미지를 다시 빌드해야 한다.
로컬 Vite 실행에서는 `frontend/.env`에 웹 설정을 넣는다. 서버와 웹 설정은 같은 Firebase 프로젝트를 사용한다.

워크로드 ID를 사용하면 기본 Compose만 사용한다. 관리되는 ADC 파일을 사용할 경우 저장소 밖의 절대 경로를
`NOTIFICATION_FIREBASE_CREDENTIALS_FILE`로 지정하고 기존 Compose 명령에
`-f compose.notification-credentials.yaml`을 추가한다. 예를 들어 `-f compose.yaml -f compose.https.yaml
-f compose.notification-credentials.yaml` 순서다. 이 overlay는 backend에만 파일을 읽기 전용으로 마운트하며
컨테이너 경로를 `GOOGLE_APPLICATION_CREDENTIALS`로 지정한다. backend UID 10001의 읽기 권한을 준비해야 한다.
개인키 내용은 `.env`, 빌드 인자, frontend 이미지에 넣지 않는다.

delivery만 끄면 알림함과 폴링은 유지된다. publisher off 기간은 기본 소급하지 않는다.
롤백은 작업과 event key를 보존하고 additive schema를 삭제하지 않는 방식으로 한다.

## 보관과 관측

알림함은 생성 후 90일까지만 API에 표시한다. SENT/SKIPPED 발송은 완료 후 30일, FAILED는 90일 보관한다.
10분마다 각각 최대 500건씩 정리한다. 참조하는 발송이 남아 있으면 보관함 물리 삭제를 기다린다.
90일 지난 완료 이벤트 payload도 참조가 없어질 때 지우며 event key는 tombstone으로 유지한다.
진행 중인 작업은 자동 삭제하지 않는다. 장기 대기 원인은 아래 지표와 SQL로 확인한다.

- `notification.queue.pending{queue=outbox|delivery}`, `notification.queue.failed`, `notification.queue.delay.seconds`: 1분 간격 집계.
- `notification.push.result{result=ACCEPTED|RETRYABLE|INVALID_REGISTRATION|PERMANENT_FAILURE|EXCEPTION}`, `notification.push.duration`.
- [읽기 전용 진단 SQL](../queries/notification-delivery-status.sql)은 개수·지연·오류 분류만 반환한다. FID·쿠키는 로그에 남기지 않는다.
- 출시 초기 제안: outbox 최고 지연 5분·delivery 실행 지연 10분 초과가 5분 유지되거나 FAILED가 증가하면 확인한다.
  이는 측정 전 임시 기준이다. 실제 유입량과 처리율을 측정해 운영 알람과 수치 조정을 별도 적용한다.

FAILED 재처리는 원인과 정확한 ID·발생 시각을 검토하고 관련 워커를 끈 뒤 수행한다.
24시간 이내, 아직 유효한 구독·기기, 미전송 의도가 확인된 행만 허용한다.
발송 행은 같은 ID·notification ID를 유지한 채 RETRY, next_attempt_at=현재 시각, lease/finished_at=NULL로 재개한다.
최대 시도에 도달한 행은 무조건 attempts를 0으로 만들지 말고 별도 승인을 받아야 한다.
공고 연결 실패 이벤트는 같은 event key·snapshot·커서를 유지해 PENDING으로 바꾼다. COMPLETED 커서를 초기화하지 않는다.
새 이벤트 복제, 전체 실패 행 일괄 재시도, 과거 알림 재전송은 금지한다. FCM 접수 여부가 불명확하면 중복 가능성을 먼저 확인한다.

# 관심 단지·지역의 새 공고 알림

관심 설정은 `interest`, 실제 알림 생성·조회·전달은 `notification`이 담당한다.
읽지 않은 개수의 원본은 DB다. FCM은 갱신 신호와 기기 알림 전달 수단이며 수신 횟수로 개수를 계산하지 않는다.
SSE·WebSocket·메시지 브로커는 사용하지 않는다.

## 생성과 전달

1. 관리자 직접 등록·가져오기·MyHome 정제의 신규 공고 저장 트랜잭션에서 아웃박스를 함께 기록한다.
2. 고정한 활성화 시각 이후 생성됐고, 공식 게시일이 그 시각의 한국 날짜 이상인 ORIGINAL만 대상이다.
   기존 공고 재수집, 과거 게시일, 정정·취소 공고, 게시일 누락은 새 공고 알림을 만들지 않는다.
3. 10초 주기의 materializer가 이벤트 한 건을 `FOR UPDATE SKIP LOCKED`로 잠근다.
   공개 공고에 연결된 단지와 매칭 지역 코드를 snapshot으로 보관한다. 연결이 없으면 60초 후 재확인하고 24시간 후 FAILED다.
4. 발생 당시 활성화된 구독 중 현재도 유효한 사용자를 200명씩 처리한다. 단지·지역 중복은 사용자별 한 건으로 합친다.
   snapshot의 사용자 상한·수신자 커서, 보관함 INSERT, 당시 활성 기기의 delivery INSERT를 같은 트랜잭션으로 저장한다.
   나중에 기기를 켜도 이미 생성된 보관함을 소급 전송하지 않는다.
5. 5초 주기의 delivery worker는 한 번에 한 건, 최대 20건을 처리한다. 120초 lease와 임의 토큰으로 선점한 후 commit한다.
   구독·공고 가용성·기기 사용자·binding version을 다시 확인하고 DB 트랜잭션 밖에서 FCM을 호출한다.
6. 호출 제한은 30초, 연결 5초·읽기/쓰기 각각 10초다. 결과는 lease 토큰이 일치할 때만 반영한다.
   timeout·일시 오류는 60초부터 최대 1시간 backoff와 jitter, Retry-After 하한을 사용한다. 최대 8회 또는 이벤트 발생 후 24시간이다.
   UNREGISTERED만 기기를 끄고, 일반 INVALID_ARGUMENT·인증/권한 오류는 FAILED로 남긴다.

원본 공고의 물리 삭제 후에도 event key와 알림 이력을 유지하므로 공고 ID에는 FK가 없다. 조회 시 현재 가용성을 확인한다.
사용자·기기·보관함·발송 간 FK와 사용자 탈퇴 cascade는 유지한다. event key는 source identifier로 중복을 방지한다.

## 전달 보장의 한계

SENT는 FCM의 요청 접수이며 기기 표시나 읽음의 증거가 아니다. FCM 접수 후 DB 저장 전에 프로세스가 죽으면 재발송할 수 있다.
보관함의 `(user_id,event_id)`와 delivery의 `(notification_id,device_id,binding_version)`은 유일하다.
웹 푸시 tag는 notification ID를 사용해 반복 표시를 줄인다. exactly-once 전달은 보장하지 않는다.

검증 직후 구독 취소·로그아웃과 이미 시작한 외부 발송의 경합은 남는다. 이미 OS에 표시된 알림도 회수하지 못한다.
따라서 payload는 일반 문구·notification ID·`/notifications` 링크만 담는다. 계정·지역·단지·자격 정보는 넣지 않는다.

## 회원 API

모두 ROLE_USER를 요구한다. 쓰기는 기존 CSRF 토큰을 사용하고 성공 응답은 no-store다. ID는 JSON 문자열이다.

| API | 동작 |
|---|---|
| GET `/api/v1/notifications/unread-count` | 만료되지 않은 미읽음 개수 |
| GET `/api/v1/notifications?size=20&cursor=...` | `(created_at,id)` 역순, nextCursor와 readThrough |
| POST `/api/v1/notifications/{id}/read` | 본인 알림 개별 읽음, 멱등 |
| POST `/api/v1/notifications/read-all` | `{readThrough}`까지 읽음 |
| GET `/api/v1/notification-push-devices/current` | 현재 기기 상태와 bindingVersion |
| POST `/api/v1/notification-push-devices` | `{fid, expectedUserId, expectedBindingVersion}` 등록 |
| DELETE `/api/v1/notification-push-devices/current` | `{expectedUserId, bindingVersion}`로 해제 |

readThrough는 목록과 같은 DB snapshot의 본인 최대 ID다. 사용자별 advisory transaction lock을 획득한 뒤 ID를 발급하며,
모두 읽음도 같은 잠금을 사용한다. 조회 이후 생성된 알림을 읽음으로 바꾸지 않는다. GET은 읽음 상태를 바꾸지 않는다.
기기 UUID는 HttpOnly·SameSite=Lax 쿠키이며 운영에서는 Secure가 필요하다. IP/User-Agent로 식별하지 않는다.
다른 기기가 같은 FID를 주장하면 409다. A→B→A 교체·재동의·FID 변경 때 binding version이 증가한다.
409는 새로운 사용자 동작과 상태 확인을 요구하며 최신 버전으로 무조건 덮어쓰지 않는다.

소셜 로그인 성공 시 SDK와 무관하게 세션에 현재 사용자의 기기 버전을 기록한다. 등록·현재 상태 조회도 확인한 버전을 저장한다. 로그아웃은 그 버전만 서버에서 해제한 뒤 세션을 폐기한다.
현재 로컬 HttpSession mutex로 등록·로그인 확인·로그아웃을 직렬화한다. 세션을 분산 저장소로 바꿀 때 이 직렬화도 검토해야 한다.
오래된 활성 바인딩의 명시적 해제는 409다. 같은 해제 재시도는 204이며 버전을 중복 증가시키지 않는다.
끄기·로그아웃은 서버 바인딩만 비활성화하고 Firebase installation을 unregister하지 않는다. installation은 탭과 계정이
공유하므로 SDK 삭제가 다른 탭의 새 등록을 끊을 수 있다. 브라우저 권한과 설치는 유지되며, 발송 직전 서버 검증으로
후속 전송을 차단한다. 이미 FCM에 접수된 메시지는 표시될 수 있다. 로그아웃 UI 초기화는 SDK 응답을 기다리지 않는다.

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
- [읽기 전용 진단 SQL](../../docs/queries/notification-delivery-status.sql)은 개수·지연·오류 분류만 반환한다. FID·쿠키는 로그에 남기지 않는다.
- 출시 초기 제안: outbox 최고 지연 5분·delivery 실행 지연 10분 초과가 5분 유지되거나 FAILED가 증가하면 확인한다.
  이는 측정 전 임시 기준이다. 실제 유입량과 처리율을 측정해 운영 알람과 수치 조정을 별도 적용한다.

FAILED 재처리는 원인과 정확한 ID·발생 시각을 검토하고 관련 워커를 끈 뒤 수행한다.
24시간 이내, 아직 유효한 구독·기기, 미전송 의도가 확인된 행만 허용한다.
발송 행은 같은 ID·notification ID를 유지한 채 RETRY, next_attempt_at=현재 시각, lease/finished_at=NULL로 재개한다.
최대 시도에 도달한 행은 무조건 attempts를 0으로 만들지 말고 별도 승인을 받아야 한다.
공고 연결 실패 이벤트는 같은 event key·snapshot·커서를 유지해 PENDING으로 바꾼다. COMPLETED 커서를 초기화하지 않는다.
새 이벤트 복제, 전체 실패 행 일괄 재시도, 과거 알림 재전송은 금지한다. FCM 접수 여부가 불명확하면 중복 가능성을 먼저 확인한다.

## 의존성과 검증 범위

Firebase Admin 9.11.0과 웹 SDK 13.0.0(Apache-2.0)을 사용한다. FID 기반 register/onRegistered와 setFid는
[공식 웹 API](https://firebase.google.com/docs/reference/js/messaging), [Admin 릴리스](https://firebase.google.com/support/release-notes/admin/java)를 기준으로 한다.
별도 Web Push 암호화·브라우저별 전송 구현 대신 SDK 경계로 격리했다. Admin의 Google HTTP/GAX/인증 전이 의존성이 추가된다.
웹에서는 app/messaging만 지연 import한다. Firestore는 번들에 포함하지 않는다. 설치 의존성의 grpc-js 취약점은
Firestore 하위에만 1.13.6 override를 적용했다. 제거할 때는 웹 SDK 경계·worker·Admin sender와 이 override를 함께 확인한다.

자동 테스트는 실제 PostgreSQL 동시성·Flyway와 mock FCM 경계, jsdom 행동을 검증한다.
실제 Firebase 자격·개발 프로젝트·테스트 기기는 제공되지 않아 실제 전송과 iPhone/OS 표시·배포 worker 갱신은 미검증이다.
운영 전송은 실행하지 않았다. 실제 기기 검증 전에는 푸시 공개 플래그를 켜지 않는다.

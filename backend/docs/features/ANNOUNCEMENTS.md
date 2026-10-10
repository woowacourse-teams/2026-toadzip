# 공고 상세

공개 공고의 첨부파일·조회수·일정 대상에 적용하는 기준이다.

## 첨부파일

공고의 저장된 첨부파일을 서버에서 받아 미리보기·다운로드한다.

### API

`GET /api/v1/announcements/{announcementId}/attachments/{attachmentId}/content`

- `download=false` (기본): 실제 본문이 `%PDF-`로 시작하는 파일만 `application/pdf`, `Content-Disposition: inline`으로 반환한다.
- `download=true`: 원본 바이트를 `application/octet-stream`, `Content-Disposition: attachment`로 반환한다.
- ID는 공개 공고와 소속 첨부파일을 함께 조회한다. 삭제된 공고나 다른 공고의 첨부파일은 404다.

### 외부 파일 처리

#### 허용 출처

브라우저가 원본 URL을 전달하지 않는다. 저장된 URL의 정확한 호스트 `apply.lh.or.kr`, `www.i-sh.co.kr`, `www.gh.or.kr`만 허용한다.
HTTP 링크도 HTTPS로 요청한다. 비표준 포트·사용자 정보·fragment·리다이렉트는 거절하고 사용자 쿠키·인증 헤더는 전달하지 않는다.

#### 용량과 시간

| 항목 | 제한 |
|---|---|
| 동시 전송 | 프로세스당 4개 |
| 파일 크기 | 기본 100 MiB. `ANNOUNCEMENT_ATTACHMENT_MAX_SIZE=100MB` |
| 연결 | 5초 |
| 전체 수신 | 헤더와 본문 완료까지 120초 |

헤더가 없어도 본문 수신량을 검사하고 HTML 응답은 다운로드에서도 거절한다. 시간 초과·인터럽트 시 요청을 취소한다.
DB 트랜잭션은 외부 요청 동안 유지하지 않는다.

본문은 임시파일로 받은 뒤 스트림으로 응답한다. 정상 전송·오류·연결 종료 시 파일을 지우고 슬롯을 반환한다.
JVM의 `java.io.tmpdir`에 최대 합계 약 400 MiB와 여유 공간을 확보한다.
파일 한도를 바꾸면 `.env`를 수정하고 백엔드 컨테이너를 재생성한다. 업로드용 Nginx·multipart 제한과는 별개다.
응답은 `no-store`·`nosniff`이며 별도 파일 저장소나 캐시는 두지 않는다.

### 오류

알려진 기관이어도 리다이렉트·HTML 오류·큰 파일은 열리지 않을 수 있다. 실패하면 공고 원문으로 확인하도록 안내한다.

| 오류 코드 | HTTP | 의미 |
|---|---|---|
| ATTACHMENT_NOT_FOUND | 404 | 공고/첨부 없음 또는 비공개 |
| ATTACHMENT_NOT_PDF | 422 | PDF 미리보기 불가 |
| ATTACHMENT_UNSUPPORTED_SOURCE | 422 | 지원하지 않는 원본 URL |
| ATTACHMENT_TOO_LARGE | 413 | 파일 크기 제한 초과 |
| ATTACHMENT_BUSY | 503 | 동시 외부 요청 제한 초과 |
| ATTACHMENT_UPSTREAM_FAILURE | 502 | 외부 응답/시간 초과/빈 본문 오류 |

### 브라우저 연동

#### PDF

원본 iframe·직접 fetch 대신 위 API의 응답을 Blob URL로 표시한다.
파일 변경·모달 종료 시 요청을 취소하고 Blob URL을 해제한다.

PDF.js legacy 빌드와 같은 버전의 worker를 지연 로딩한다. 한국어 CMap·표준 폰트·WASM은 정적 자산으로 포함한다.
페이지 이동·크기 조절·현재 페이지 텍스트를 제공하고, 닫을 때 렌더링과 worker를 종료한다.

#### HWP·HWPX

`download=true`로 받은 원본을 `@rhwp/core` worker에서 읽고 페이지 SVG 이미지로 표시한다.
HTML로 변환해 DOM에 넣지 않으며 외부 변환 서비스·API 키는 필요하지 않다.
암호·손상·미지원 구조는 다운로드를 안내한다. 프론트엔드 요청 제한시간은 150초다.

## 조회수

### 집계 기준

- 같은 브라우저·공고는 `Asia/Seoul`의 날짜별 하루 1회만 집계한다. 자정에 기준 날짜가 바뀐다.
- 목록과 상세 GET은 읽기 전용이다. 프론트가 상세 응답을 정상 해석한 뒤 별도 POST로 열람을 기록한다.
- `POST /api/v1/announcements/{announcementId}/views`는 `{ "viewerId": "UUID" }`를 받으며 기존 CSRF 보호를 적용한다.
- 응답은 `{ "data": { "viewCount": 1 } }` 형태이며 중복 요청도 현재 조회수를 반환한다.
- 없는/관리자 삭제된 공고는 404, 잘못된 식별자는 400, CSRF 누락은 403이다.

### 중복·동시성

- 브라우저 UUID는 로컬 저장소 `toadzip.announcement-viewer`에 유지한다. [Web Locks](https://w3c.github.io/web-locks/)로 최초 생성과 CSRF 준비를 탭 간 직렬화한다.
- 서버는 `(announcement_id, viewer_id)`의 유일성 제약과 마지막 집계일을 사용한다. 이력은 브라우저·공고당 한 행만 유지한다.
- 공고 행 잠금 → 당일 기록 upsert → 카운터 증가가 하나의 트랜잭션이다. 실패하면 기록과 카운터가 함께 롤백된다.
- 다른 브라우저의 동시 증가는 `view_count = view_count + 1`로 누적한다.
- `Announcement.viewCount`는 일반 ORM UPDATE에서 제외해 수집·관리 수정이 오래된 조회수를 덮어쓰지 못하게 한다. 최초 INSERT 값은 보존한다.
- 상세에서 확인한 조회수는 현재 목록에도 반영하며, 늦은 목록 응답으로 감소시키지 않는다.

### 실패와 한계

- 브라우저 잠금/저장소가 없거나 차단되면 임시 식별자를 발급하지 않고 집계를 생략한다. 상세 조회는 유지한다.
- 조회 기록 요청에는 3초 제한을 둔다. 실패하면 기존 상세 조회수를 유지하고, 다음 상세 열람에서 재시도한다.
- 브라우저 데이터 삭제·시크릿 모드·다른 브라우저 프로필은 별도 방문자로 취급된다. IP나 계정 기반의 순방문자 집계가 아니다.
- 임의 UUID를 반복 전송하는 의도적인 조회수 조작 방지는 별도 속도 제한/남용 방어 범위다.
- 저장값은 무작위 브라우저 UUID, 공고 ID, 마지막 집계일이다. IP·사용자 계정·User-Agent를 저장하지 않는다.

### 배포

- API를 제공하기 전에 `V20260928_03__announcement_daily_views.sql`을 적용한다. 기존 조회수는 변경하지 않는다.
- 애플리케이션 롤백 시 추가 테이블을 유지해도 기존 GET 계약과 호환된다.

## 일정 대상

공고 상세의 `schedules[].complexName`은 LH 일정 원천의 단지명이다. 이름만으로 단지 ID를
추정하지 않는다. 단지명이 없는 원천과 수동 일정은 null을 유지한다. 추가 필드이므로 이전
프론트엔드와 호환된다. 접수 상태와 대표 날짜 계산에는 사용하지 않는다.

### 재정제

스키마 적용 후 새 서버에서 LH 재정제를 실행하면 기존 일정 ID를 유지하며 검증된 조회 조건의
원천 단지명을 채운다. 운영 재정제는 별도 배포 절차로 실행하며 코드 변경만으로 실행되지 않는다.
원천 행 순서만 이용하는 SQL 역채움은 다른 조회 조건·수집 시점의 단지를 연결할 수 있어 사용하지 않는다.
서버 롤백 시 컬럼을 남겨 두어 저장된 단지명을 보존한다.

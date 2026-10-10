# 공고 첨부파일

공고의 저장된 첨부파일을 서버에서 받아 미리보기·다운로드한다.

## API

`GET /api/v1/announcements/{announcementId}/attachments/{attachmentId}/content`

- `download=false` (기본): 실제 본문이 `%PDF-`로 시작하는 파일만 `application/pdf`, `Content-Disposition: inline`으로 반환한다.
- `download=true`: 원본 바이트를 `application/octet-stream`, `Content-Disposition: attachment`로 반환한다.
- ID는 공개 공고와 소속 첨부파일을 함께 조회한다. 삭제된 공고나 다른 공고의 첨부파일은 404다.
## 외부 파일 처리

### 허용 출처

브라우저가 원본 URL을 전달하지 않는다. 저장된 URL의 정확한 호스트 `apply.lh.or.kr`, `www.i-sh.co.kr`, `www.gh.or.kr`만 허용한다.
HTTP 링크도 HTTPS로 요청한다. 비표준 포트·사용자 정보·fragment·리다이렉트는 거절하고 사용자 쿠키·인증 헤더는 전달하지 않는다.

### 용량과 시간

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

## 오류

알려진 기관이어도 리다이렉트·HTML 오류·큰 파일은 열리지 않을 수 있다. 실패하면 공고 원문으로 확인하도록 안내한다.

| 오류 코드 | HTTP | 의미 |
|---|---|---|
| ATTACHMENT_NOT_FOUND | 404 | 공고/첨부 없음 또는 비공개 |
| ATTACHMENT_NOT_PDF | 422 | PDF 미리보기 불가 |
| ATTACHMENT_UNSUPPORTED_SOURCE | 422 | 지원하지 않는 원본 URL |
| ATTACHMENT_TOO_LARGE | 413 | 파일 크기 제한 초과 |
| ATTACHMENT_BUSY | 503 | 동시 외부 요청 제한 초과 |
| ATTACHMENT_UPSTREAM_FAILURE | 502 | 외부 응답/시간 초과/빈 본문 오류 |

## 브라우저 연동

### PDF

원본 iframe·직접 fetch 대신 위 API의 응답을 Blob URL로 표시한다.
파일 변경·모달 종료 시 요청을 취소하고 Blob URL을 해제한다.

PDF.js legacy 빌드와 같은 버전의 worker를 지연 로딩한다. 한국어 CMap·표준 폰트·WASM은 정적 자산으로 포함한다.
페이지 이동·크기 조절·현재 페이지 텍스트를 제공하고, 닫을 때 렌더링과 worker를 종료한다.

### HWP·HWPX

`download=true`로 받은 원본을 `@rhwp/core` worker에서 읽고 페이지 SVG 이미지로 표시한다.
HTML로 변환해 DOM에 넣지 않으며 외부 변환 서비스·API 키는 필요하지 않다.
암호·손상·미지원 구조는 다운로드를 안내한다. 프론트엔드 요청 제한시간은 150초다.

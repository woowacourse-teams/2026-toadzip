# 공고 첨부파일

`GET /api/v1/announcements/{announcementId}/attachments/{attachmentId}/content`

- `download=false` (기본): 실제 본문이 `%PDF-`로 시작하는 파일만 `application/pdf`, `Content-Disposition: inline`으로 반환한다.
- `download=true`: 원본 바이트를 `application/octet-stream`, `Content-Disposition: attachment`로 반환한다.
- ID는 공개 공고와 소속 첨부파일을 함께 조회한다. 삭제된 공고나 다른 공고의 첨부파일은 404다.
- 브라우저에서 원본 URL을 전달받지 않는다. 저장된 URL의 정확한 호스트 `apply.lh.or.kr`, `www.i-sh.co.kr`, `www.gh.or.kr`만 허용한다. 기존 HTTP 링크도 HTTPS로 요청한다. 비표준 포트, 사용자 정보, fragment와 리다이렉트는 거절한다. 사용자 쿠키나 인증 헤더를 전달하지 않는다.
- 파일 전송은 프로세스당 4개, 파일은 기본 100 MiB까지다. `ANNOUNCEMENT_ATTACHMENT_MAX_SIZE=100MB`로 크기를 설정한다. 연결 타임아웃은 5초, 헤더 수신과 본문 완료를 포함한 전체 제한시간은 120초다. 시간 초과나 인터럽트 시 HTTP 요청을 취소한다. 크기 제한은 헤더가 없어도 본문을 수신하면서 적용한다. HTML 응답은 다운로드에서도 거절한다. DB 트랜잭션을 외부 요청 동안 유지하지 않는다. 본문은 임시파일로 수신한 뒤 스트림으로 응답해 전체 파일을 Java 힙에 올리지 않는다. 정상 전송·오류·클라이언트 연결 종료 시 파일을 삭제하고 슬롯을 반환한다. 임시 디스크에 동시 4개 파일의 최대 합계 약 400 MiB와 여유 공간을 확보한다.
- 응답은 `no-store`, 파일은 `nosniff`다. 별도 파일 저장소나 캐시를 만들지 않는다.
- 알려진 기관이어도 리다이렉트, HTML 오류, 큰 파일 등은 열리지 않을 수 있다. 화면에서 오류를 안내하고 기존 공고 원문으로 확인할 수 있다.

| 오류 코드 | HTTP | 의미 |
|---|---|---|
| ATTACHMENT_NOT_FOUND | 404 | 공고/첨부 없음 또는 비공개 |
| ATTACHMENT_NOT_PDF | 422 | PDF 미리보기 불가 |
| ATTACHMENT_UNSUPPORTED_SOURCE | 422 | 지원하지 않는 원본 URL |
| ATTACHMENT_TOO_LARGE | 413 | 파일 크기 제한 초과 |
| ATTACHMENT_BUSY | 503 | 동시 외부 요청 제한 초과 |
| ATTACHMENT_UPSTREAM_FAILURE | 502 | 외부 응답/시간 초과/빈 본문 오류 |

원본 LH 첨부는 실제 PDF여도 octet-stream/attachment이고 CORS 허용 헤더가 없을 수 있다. 따라서 브라우저가 원본 URL을 직접 fetch하거나 iframe으로 표시하는 대신 위 API를 사용한다.

PDF·HWP·HWPX의 Blob·worker·객체 URL 정리, 검색·다운로드와 미지원 형식의 화면 처리는 [브라우저 문서 뷰어](viewers.md)를 따른다.

## 설정과 배포 확인

- `ANNOUNCEMENT_ATTACHMENT_MAX_SIZE=100MB`는 기본 100 MiB(104,857,600바이트)이며 Compose가 백엔드에 전달한다. 생략해도 같은 기본값이다.
- 임시 디렉터리는 기본 `java.io.tmpdir`이다. 동시 요청 4개의 파일 최대 합계 약 400 MiB와 여유 디스크를 확보한다.
- 이 값은 다운로드 응답 제한이다. Nginx `client_max_body_size`나 Spring multipart 업로드 제한을 높여 해결하지 않는다.
- 한도 변경은 서버 `.env` 수정과 백엔드 재생성이 필요하다. 뷰어·Nginx 변경은 프론트 이미지도 함께 갱신하며 [현재 배포 절차](../../operations/deployment.md)를 따른다.
- 배포 후 기존 413 파일의 200 응답, PDF worker의 JavaScript MIME, HWP·HWPX 페이지 표시와 원본 다운로드를 실제로 확인한다. 검사 통과를 실제 외부 파일·브라우저 검증으로 대신하지 않는다.

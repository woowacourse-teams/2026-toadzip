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

LH 공개 첨부 샘플(fileid=68442951)은 실제 PDF지만 octet-stream/attachment이고 CORS 허용 헤더가 없었다. 따라서 원본 URL을 iframe에 넣거나 브라우저에서 직접 fetch하는 대신 이 경로를 사용한다. 프론트엔드는 응답을 Blob URL로 표시하고 모달을 닫거나 파일을 바꾸면 요청을 취소하고 URL을 해제한다. PDF 렌더링은 지연 로딩한 PDF.js legacy 빌드와 같은 버전의 worker를 사용한다. 한국어 CMap·표준 폰트·WASM은 빌드 시 정적 자산으로 포함한다. 페이지 이동·크기 조절·현재 페이지 텍스트를 제공하고, 닫을 때 렌더링과 worker를 종료한다.


HWP·HWPX 미리보기는 `download=true`의 원본을 브라우저 `@rhwp/core` worker로 읽는다. PDF 전용 `download=false`의 검증은 유지한다. 한글 파일을 HTML로 변환해 DOM에 넣지 않고 페이지 SVG 이미지를 표시하며 외부 변환 서비스나 API 키는 필요하지 않다. 암호·손상·미지원 구조는 다운로드로 안내한다. 프론트엔드 요청 제한시간은 150초다.

# PDF 보정 초안

관리자가 PDF 보정 화면에서 진행 중인 작업을 새로고침·화면 이동·브라우저 재시작 뒤에도 이어 가도록 서버에 임시저장하는 계약이다. 초안은 관리자별·공고별로 하나이고, 로그인한 관리자 본인의 초안만 읽고 쓴다.

## 저장하는 것과 하지 않는 것

- `payload`는 화면이 만든 작업 상태 JSON 객체다. 행 포함·제외, 값 선택(기존·추출·직접 입력), 미완성 입력, 분리·연결·추가 계획, 단지·주택형·공급행 연결, 일정·접수처·임대조건 수정, 사유·근거, 검토 진행이 여기에 들어간다. 서버는 내용을 해석하거나 도메인 규칙으로 검증하지 않으므로 미완성·잘못된 값도 저장된다.
- 서버가 확인하는 것은 JSON 객체인지와 직렬화한 크기가 2MB(2,097,152바이트) 이하인지뿐이다.
- 초안 저장·삭제는 공고·공급행·일정 같은 공개 데이터와 변경 이력을 바꾸지 않는다. 공개 데이터 반영은 [기존 공고 선택 갱신](announcement-import.md#기존-공고-선택-갱신)의 미리보기·적용으로만 한다.
- 적용 서비스는 초안을 지우지 않는다. 화면이 적용 성공 응답을 받은 뒤에만 초안을 삭제한다.
- 공고를 지우면 그 공고의 초안도 함께 지워진다.

## API

경로는 `/api/admin/announcements/{id}/correction-draft`다. 다른 관리자 API와 같이 관리자 세션이 필요하고, `PUT`·`DELETE`는 CSRF 토큰이 필요하다.

| 요청 | 성공 | 내용 |
|---|---|---|
| `GET` | `200` | 이 관리자의 초안. 없으면 `data`가 `null`이다. |
| `PUT` | `200` | 저장 후 `data.revision`, `data.updatedAt` |
| `DELETE ?expectedRevision=N` | `204` | 저장된 revision이 N이면 지운다. 초안이 이미 없어도 `204`다. |

`PUT` 요청 본문:

| 필드 | 필수 | 의미 |
|---|---|---|
| `expectedRevision` | 첫 저장은 생략 또는 `null` | 화면이 마지막으로 받은 revision |
| `baseSnapshotToken` | 예 | 작업을 시작할 때 받은 `json-corrections` 작업 공간의 `snapshotToken`(64자 이하) |
| `pdfSha256` | 아니요 | 고른 공고문 PDF의 SHA-256(소문자 16진수 64자) |
| `extractionId` | 아니요 | 작업에 쓴 PDF 추출 작업 ID(`pdf-extractions/jobs/{jobId}`의 jobId) |
| `payload` | 예 | 작업 상태 JSON 객체 |

`GET` 응답 `data`는 `payload`, `revision`, `baseSnapshotToken`, `pdfSha256`, `extractionId`, `updatedAt`(ISO 8601 UTC), `stale`이다. `stale`은 초안의 `baseSnapshotToken`이 지금 공고의 `json-corrections` 작업 공간 `snapshotToken`과 다르면 `true`다. 초안을 만든 뒤 공고·공급정보·일정·원천이 바뀌었거나 이미 적용한 뒤 남은 초안이라는 뜻이므로, 화면은 복원할 때 최신값과의 차이를 보여 준다.

## revision 충돌

revision은 첫 저장이 1이고 저장할 때마다 1씩 는다. 저장·삭제는 저장된 revision이 `expectedRevision`과 같을 때만 반영되는 조건부 한 문장이라, 여러 탭이나 늦게 도착한 요청이 최신 초안을 덮어쓰지 않는다.

다음 경우 `409 ANNOUNCEMENT_CORRECTION_DRAFT_CONFLICT`로 거절하고 초안은 그대로 둔다.

- `expectedRevision`이 저장된 revision과 다르다.
- 첫 저장(`expectedRevision` 없음)인데 초안이 이미 있다.
- `expectedRevision`을 보냈는데 그사이 초안이 지워졌다. 늦은 저장이 지운 초안을 되살리지 않는다.

응답에는 `code`, `message`, `traceId`와 함께 `currentRevision`, `currentUpdatedAt`이 있다. 초안이 지워졌으면 둘 다 `null`이다. 화면은 자동으로 다시 덮어쓰지 않고 충돌을 보여 준 뒤 최신 초안을 불러올지 관리자가 고르게 한다.

## 그 밖의 오류

| 상태 | code | 조건 |
|---|---|---|
| `400` | `INVALID_REQUEST` | `payload`가 객체가 아님, 정의되지 않은 필드 |
| `400` | `VALIDATION_FAILED` | `baseSnapshotToken`·`payload` 누락, `pdfSha256` 형식 오류, `expectedRevision`·`extractionId`가 1 미만 |
| `404` | `ANNOUNCEMENT_NOT_FOUND` | 없는 공고 |
| `413` | `ANNOUNCEMENT_CORRECTION_DRAFT_TOO_LARGE` | `payload`가 2MB 초과 |

## 스키마

`V20261103_03__announcement_correction_drafts.sql`이 `announcement_correction_drafts` 테이블을 만든다. `(announcement_id, admin_login_identifier)`가 고유하고, 공고 외래 키는 `ON DELETE CASCADE`다. 기존 데이터를 바꾸지 않으므로 이전 백엔드로 되돌려도 테이블만 남고 동작에 영향이 없다.

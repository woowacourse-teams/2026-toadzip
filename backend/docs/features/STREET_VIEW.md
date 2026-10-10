# 거리뷰

백엔드는 제공 정책·초기 실행 정보·초기화 결과를 관리한다. 촬영 지점 조회·영상 표시는 브라우저 네이버 SDK가 담당한다.
화면 전환·iframe·핀은 [프론트엔드 거리뷰](../../../frontend/docs/street-view.md)를 따른다.

## API

| 요청 | 역할 | 권한 |
|---|---|---|
| GET `/api/v1/complexes/{complexId}/street-view` | 실행 정보 | 익명 허용 |
| GET `/api/admin/street-view-policy` | 정책 조회 | ADMIN |
| PUT `/api/admin/street-view-policy` | 정책 변경 | ADMIN·CSRF |
| GET `/api/admin/street-view-policy/changes?page=0` | 변경 이력 | ADMIN |
| POST `/api/v1/street-view/events` | 초기화 결과 | 익명 허용·CSRF |

필드·검증 스키마는 OpenAPI를 따른다.

## 실행 정보

단지 alias를 해석한 실제 ID와 WGS84 출입구 좌표를 사용한다.
`searchPosition`은 촬영 지점 검색 기준, `lookAtPosition`은 시선 대상이며 둘 다 출입구 좌표다. 초기 tilt는 0·fov는 90이다.
실제 촬영 위치가 달라질 수 있어 pan은 브라우저가 계산한다.

| 상태 | 응답 |
|---|---|
| 정책 비활성 | `enabled=false`, `POLICY_DISABLED` |
| 좌표 없음·범위 밖·(0, 0) | `enabled=false`, `INVALID_COORDINATES` |
| 단지 없음·삭제 | `404 COMPLEX_NOT_FOUND` |
| 정책 행 없음 | `503 STREET_VIEW_POLICY_UNAVAILABLE` |

비활성일 때 `initialization`은 null이다. 활성은 시도 허용이며 실제 촬영 데이터·외관 식별 성공을 보장하지 않는다.
`policyRevision`은 전체 정책의 버전이며 좌표·촬영 데이터 버전은 아니다. 응답은 `no-store`다.

## 정책 변경

`street_view_policies`의 단일 정책은 처음에 `enabled=false`, `version=0`이다.
관리자는 현재 `version`·변경할 `enabled`·사유를 보내며 사유는 공백 제거 후 1~500자다. 작업자는 인증 정보에서 얻는다.

다른 관리자의 선행 변경은 `409 ADMIN_DATA_CONFLICT`로 거절한다. 버전을 다시 조회한 뒤 요청한다.
제공 여부·사유가 모두 같으면 변경하지 않고 사유만 달라도 버전·이력을 갱신한다.
정책·`admin_data_changes`를 함께 저장하므로 감사 저장 실패 시 정책도 롤백한다. 이력은 최신순·20건씩 조회한다.

정책은 실행 정보 요청마다 읽는다. 비활성화는 신규 실행에 적용하며 이미 열린 거리뷰를 강제로 닫지 않는다.
활성화 전 실제 SDK 인증·영상·초기 방향·실패 안내·기존 지도 복귀를 확인한다. API 성공만으로 정상 표시를 판단하지 않는다.

## 초기화 결과

### 이벤트

활성 실행마다 UUID `attemptId`를 만들고 단지 ID·`policyRevision`·종류·단계·사유·`durationMs`를 보낸다.

| 종류 | 의미 |
|---|---|
| STARTED | 시작. DOCUMENT 단계·0ms |
| READY | SDK 초기화·파노라마 조회 성공. 외관 식별 성공은 아님 |
| FAILED | DOCUMENT·SDK·PANORAMA 단계 실패. 조회 실패를 촬영 데이터 없음으로 단정하지 않음 |
| CANCELLED | 초기화 전에 닫거나 대상 변경 |

최초 시작·최초 종료를 각각 한 번 집계한다. 종료가 먼저 와도 허용하며 중복·다른 종료 결과는 최초 값을 유지하고 204를 반환한다.
같은 시도의 단지·정책 버전이 다르면 409다. 정책이 바뀐 뒤에도 시작된 시도의 결과는 받는다.
정상 초기화 후 이동·회전·확대·닫기는 대상이 아니다.

CSRF는 `GET /api/auth/csrf`로 준비한다. 결과 전송 실패가 거리뷰를 막아서는 안 되며 자동 재전송하지 않는다.
SDK 오류 원문·키·URL·좌표·회원 정보는 이벤트·로그에 넣지 않는다.

### 집계 제한

결과는 영구 DB 대신 Micrometer·구조화 로그에 기록한다.

| 대상 | 상한·초과 응답 |
|---|---|
| 본문 | 4 KiB·413 |
| 요청량 | 프로세스당 초당 20건, 순간 100건·429 |
| 중복 정보 | 최초 수신부터 10분, 최대 10,000개. 신규 시도 수용 불가 시 503 |

프로세스 재시작·다중 인스턴스·만료로 중복되고 브라우저 종료로 누락될 수 있다.
결과 없는 시도를 성공·실패로 추정하거나 정확한 과금·자동 차단 근거로 쓰지 않는다.
장애는 실행 정보의 HTTP·traceId, 초기화의 단계·사유, 수집의 거절 지표로 구분해 확인한다.

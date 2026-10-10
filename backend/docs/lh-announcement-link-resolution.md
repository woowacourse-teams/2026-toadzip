# LH 공고 연결과 복구

공급·상세의 성공 연결과 원천이 모두 현재 요청 해시·`panId`에 맞아야 정제한다.
한쪽만 새 요청으로 수집됐거나 연결이 다르면 기존 제품을 유지하고 정제를 보류한다.

## 요청 결정

마이홈의 활성 원천 중 최신 회차를 사용한다. 모두 비활성이면 마지막 관찰 회차를 사용한다.
LH 목록의 조회 코드가 유일하게 일치할 때 보완하고 없거나 모호하면 마이홈 해석을 유지한다.
현재 원천의 요청이 서로 다르면 실패를 남기고 기존 연결을 보존한다. URL의 `panId`만으로 원천을 고르지 않는다.
수집 대상·저장은 [공고 원천 관리](announcement-source-lifecycle.md#lh)를 따른다.

## 데이터 보호

### 수집 응답

| 응답 | 처리 |
|---|---|
| `dsSch` 조회 조건 없음·불일치 | 저장 거절 |
| 기존 상세가 있는데 새 상세가 비어 있음 | 교체 거절 |
| 기존 공급행이 있는데 빈 응답·행 누락 | 교체 보류 |
| 비교할 기존 원천이 없는 최초 빈 응답 | 허용. 성공 연결 확인 후 마이홈 정보로 정제 |

거절·보류 시 이전 성공 원천·연결을 보존한다.
공급 비교는 단지명·주택형·전용면적·공급면적과 중복 수를 확인한다. 값이 다른 중복은 세대수·금액별 개수도 보존해야 한다.
추가·순서 변경은 허용하며 수집 버전이 바뀌어도 같은 조회 조건의 기존 원천을 보호한다.

누락 검사는 최초 응답의 완전성까지 보장하지 않는다.
실제 철회·정정은 [확인된 공급 감소 반영](lh-announcement-quality-operations.md#확인된-공급-감소-반영)을 따른다.

### 제품 반영

기존 LH 보강 공고의 매핑 변경과 보강은 함께 저장한다. 매칭이 모호하거나 새 주택형 금액이 없으면 함께 롤백한다.
최초 공고는 성공 연결을 확인한 마이홈 매핑을 공개하며 보강 실패만으로 공고·공급행을 제거하지 않는다.
보강 성공 시 이전 연결의 일정·첨부·매칭된 공급행의 LH 금액을 교체하고 수동 입력은 보존한다.

## 복구

| 실패 사유 | 확인 |
|---|---|
| `LH_COLLECTION_REQUEST_UNSUPPORTED` | 현재 URL·공급유형 |
| `LH_COLLECTION_LINK_NOT_FOUND` / `LH_COLLECTION_LINK_MISMATCH` | 공급·상세 중 빠지거나 현재 요청과 다른 연결 |
| `LH_SUPPLY_SOURCE_NOT_FOUND` / `LH_DETAIL_SOURCE_NOT_FOUND` | 원천·성공 기록의 저장 상태 |

관리자 세션·CSRF로 다음 순서대로 실행한다. `{pblancId}`는 마이홈 공고 ID다.

1. 원인을 해결하고 `POST /api/admin/ingest/pipelines/announcement-collection`으로 수집한다.
   특정 공고·오래된 종료 공고는 아래 두 API로 재조회한다.
2. 수집 종료와 공급·상세의 현재 성공 연결을 확인한다.
3. `POST /api/admin/ingest/pipelines/announcement-refinement`로 정제하고 실패 기록·제품 결과를 확인한다.

```text
POST /api/admin/ingest/lh/announcements/supplies/{pblancId}/refresh
POST /api/admin/ingest/lh/announcements/details/{pblancId}/refresh
```

정제만 재실행하면 외부 변경을 가져오지 않는다. 금액·일정 확인은 [LH 품질](lh-announcement-quality-operations.md)을 따른다.

# 신청자격 판정과 표시 계약

입력한 공통 사용자 정보로 어떤 실제 모집 선택지에 지원할 수 있는지 판정한다.
조건·경로·공고 집계와 결과 표시의 원본은 이 문서다. 작성할 모집 조합은 [공고 구조화](authoring.md),
입력의 의미는 [공통 입력](profile.md), 물리 저장·동시성은 [저장 설계](storage.md)를 따른다.

## 단건 조회

`GET /api/v2/announcements/{announcementId}/eligibility?region=41465`

USER 세션 인증이 필요하고 `{"data": ...}` 응답과 `Cache-Control: no-store`를 반환한다. 회원 ID는 인증 세션에서 가져온다.
`region`은 선택한 탐색 지역이며 생략할 수 있다. 지역 선택을 프로필이나 알림 설정에 저장하지 않는다.

| 응답 | 의미 |
|---|---|
| requestedAnnouncementId / announcementId | 요청 ID와 정정 체인의 실제 최신 공고 ID |
| availability | AVAILABLE / NOT_STRUCTURED / NOT_PUBLISHED / CANCELLED |
| ruleSetId / profileVersion | 현재 공개 내용 ID와 현재 사용자 입력 수정 식별값 |
| applicationStatus | 공통 접수 상태. APPLYING / BEFORE_APPLICATION / CONDITIONAL만 현재 재판정 대상 |
| freshness | CURRENT(현재 기준 완료) / RECHECKING(현재 기준 처리 중) / NOT_AVAILABLE(대상 밖) |
| staleReasons | PROFILE_CHANGED / NOTICE_CHANGED / NOT_EVALUATED_YET. 개인정보와 공고 변경을 구분 |
| evaluatedAt / evaluatedProfileVersion | 마지막 완료 시각·당시 입력 버전. 완료 결과가 없으면 null |
| result | 아래의 개인 판정. 현재 기준의 완료 결과만 반환하고 처리 중·대상 밖이면 null |
| content | 현재 공개본의 구조화 내용. 근거·자격·물량·임대조건·일정 연결. 이전 결과와 섞지 않음 |
| selectedRegion / eligibleOptionIdsInRegion | 선택한 지역 안에서 지원 가능한 실제 선택지 ID. 지역 생략 시 전체 지원 가능 선택지 |

조회는 같은 DB 스냅샷에서 현재 기준과 저장된 최신 결과를 비교한다. 화면 요청에서 전체 판정을
다시 실행하지 않는다. 프로필·정정·공개본·공개 상태·접수 상태·평가기 중 하나라도 달라졌으면
이전 result를 숨긴다. RECHECKING을 정보 부족이나 직접 확인 필요로 표시하지 않는다.
지역 안의 지원 가능 ID도 현재 완료 결과가 있을 때만 반환한다.

이전 API를 소비하는 화면의 연결 상태와 해석 한계는 [호환 계약](compatibility.md)을 따른다.

## 저장 완료·재판정 완료·새 지원 가능 안내

`PUT /api/v2/me/eligibility-profile` 성공은 입력 저장 완료다. 응답의 새 `version`과
`reevaluationState=PENDING`을 받아 “저장했어요. 현재 정보로 다시 확인하고 있어요”로 안내한다.
판정은 백그라운드에서 처리한다. 결과 보존·오래된 작업 차단은 [저장 설계](storage.md#갱신과-복구)를 따른다.

| 메서드·경로 | 응답·동작 |
|---|---|
| `GET /api/v2/eligibility/processing` | profileVersion, requestedVersion, state(PENDING/RUNNING/DONE), failedAttempts |
| `GET /api/v2/eligibility/discoveries?afterAnnouncementId=0&limit=25` | 현재 기준에서 아직 확인 처리하지 않은 새 지원 가능 선택지. limit은 1~100 |
| `POST /api/v2/eligibility/discoveries/{rootAnnouncementId}/ack?eventId={UUID}` | CSRF 필요. 해당 발견 확인 처리, 204. 같은 eventId의 재시도는 성공, 오래된 ID·기준은 409 |

모두 USER 인증과 no-store를 적용한다. 처리 상태는 **회원 정보 수정에 따른 순회 작업**의
진행 상태다. DONE이어도 별도로 공고가 정정될 수 있으므로 상세의 freshness를 함께 확인한다.
실패 횟수가 있어도 입력이 사라진 것은 아니며 서버가 재시도한다. 재입력을 복구 조건으로 안내하지 않는다.

다음은 가상 ID를 사용한 발견 응답 예시다. 실제 공고 데이터나 현재 발견 건수가 아니다.

```json
{
  "data": {
    "items": [
      {
        "eventId": "3268c64f-d389-478e-aae9-75504c633635",
        "rootAnnouncementId": 100,
        "announcementId": 103,
        "optionIds": [
          "general-unit-a"
        ],
        "discoveredAt": "2026-11-06T10:00:00Z"
      }
    ],
    "nextCursor": 100,
    "hasMore": false
  }
}
```

상세 API의 content와 optionIds로 어떤 신청 단위·신청자격에 지원 가능한지 보여준다.
목록의 개수는 해당 페이지의 발견 수이며 전체 공고 건수가 아니다. 기준이 바뀐 후보를 숨기므로
items가 비어 있어도 hasMore이면 nextCursor로 계속 조회한다. 새로 확인할 때는 커서 0부터 시작한다.
확인 처리 후에는 반복 안내에서 제외한다. 미충족이었다가 다시 가능해지거나 다른 선택지가 새로
가능해지면 새 eventId를 생성하므로 사용자·공고 평생 1회로 제한하지 않는다.

발견 조회·확인은 안내를 연결할 수 있는 기반이다. 전달 방식과 채널별 중복 기준은 미결이며,
별도 발송을 연결할 때는 아래 [관심 알림 규칙](#검색과-관심-알림의-연결-규칙)을 적용한다.

## 결과와 표시

| result.status 및 options[].status | 의미 |
|---|---|
| ELIGIBLE | 적용되는 공통 조건과 경로 조건이 충족되고 미확인 필수 자격이 없는 실제 모집 선택지 |
| INELIGIBLE | 확인된 미충족. 다른 조건이 미입력이거나 법정 자격 미확인이어도 유지 |
| UNDETERMINED | 필요한 입력 또는 필수 자격·적용 기준 확인이 남아 있음 |
| NOT_EVALUATED | 실제 모집 여부·구조화 범위 때문에 평가하지 않음. 개인 미충족이 아님 |

공고 전체는 실제 모집 선택지 하나라도 ELIGIBLE이면 ELIGIBLE이다. 모든 실제 경로가 확인된
미충족이거나 진짜 공통 요건이 실패했을 때 INELIGIBLE이다. 없는 경로나 모집 여부 UNKNOWN을
모두 실패로 세지 않는다. RECRUITING과 UPCOMING을 평가하고 CANCELLED/NOT_RECRUITING은
지원 가능 선택지에 넣지 않는다. 일부 취소를 다른 선택지의 미충족으로 확대하지 않는다.

`APPLICATION_ROUTES`는 작성자가 실제 경로와 요구 조건을 구조화한 계약이다. 기존의
GENERAL/COMMON_ONLY 부분 비교 내용만으로 최종 지원 가능을 승격하지 않는다. 공개 전 원문
검토에서 실제 경로 누락과 자격·선정·임대료 조건의 혼동을 확인해야 한다.

| result 내부 자료 | 화면 연결 |
|---|---|
| commonChecks | 모든 실제 경로에 적용되는 공통 조건 결과 |
| paths[].pathId / checks | 경로별 조건 결과. 공통 조건을 선택지 수만큼 복제하지 않음 |
| options[].optionId / supplyUnitId / unitLabel / regionCodes | 실제 신청 단위와 지역. 묶음 단위는 content.supplyUnits의 원문 묶음 유지 |
| options[].pathId / pathLabel | 신청자격 경로와 사용자 표시명. 원문 항목명을 선정 순위로 해석하지 않음 |
| options[].message / missingInputMessage | 확인된 미충족을 먼저 보여주고 필요한 다른 입력을 별도로 안내 |
| options[].missingInputs | 서비스가 수집하지만 해당 조건에 필요한 값이 없는 입력 코드. 입력 카탈로그의 항목명·단위·도움말 연결 |
| options[].requiredQualificationIds | content.qualifications의 정확한 필수 자격을 직접 확인. 정보 입력 안내와 구분 |
| options[].directCheckGuidanceIds | 자동으로 확인할 수 없는 적용 범위나 기존 별도 자격 안내 |
| options[].exclusionGuidanceIds | “신청 불가능한 특수 경우”에 표시할 content.guidances. 이 경고만으로 보류하지 않음 |

한부모 가족 형태나 탐색 분류 SINGLE_PARENT_FAMILY로 법정 지원대상 한부모가족을 충족 처리하지
않는다. 상세에서는 정확한 이름과 요구 조건을 보여주고, “한부모가족이더라도 이 공고의
‘지원대상 한부모가족’에 해당하는지 확인해 주세요”라고 안내한다.

## 조건별 근거

각 check에는 id, label, reading, evidenceIds, directCheckId가 있다. reading.status는
SATISFIED / NOT_SATISFIED / INPUT_NEEDED / DIRECT_CHECK_REQUIRED이며 missingInputs를
함께 반환한다. AND의 확인된 미충족은 다른 미입력으로 사라지지 않고 OR의 충족은 다른
대안의 미입력 때문에 보류하지 않는다. 해당 경로에서는 미충족 조건을 먼저 나열한다.

수치 조건은 `reading.comparedValues`를 사용한다.

| 키 | 표시 의미 |
|---|---|
| field / fieldLabel | 검사한 입력 코드 / 항목명(금액 조건). check.label도 함께 제공 |
| providedValue | 사용자가 입력한 값 |
| operator | LTE(이하), EQ(같음), BETWEEN_INCLUSIVE(하한 이상·상한 이하) |
| comparisonLimit 또는 minimum/maximum | 기준값 또는 포함 범위 |
| unit / comparedValue / referenceDate | 원·개월·나이·일수 등 비교 단위와 계산값·공고 기준일 |
| basis | 소득 기준표 종류·연도·가구원수·적용 비율 등 계산 근거 |

아래 사천벌리1 사례처럼 자산 3억원·소득 미입력은 INELIGIBLE을 먼저 보여준다. 자산 항목에 `LTE`,
`comparisonLimit=245000000`, `providedValue=300000000`, `unit=KRW`를 표시하고 소득은
“입력하면 소득 조건도 확인해드릴게요”로 안내한다. 자산 미충족이 소득 추가로 해소된다고 쓰지 않는다.

NUMBER_RANGE는 입력한 인원·지역 거주 완료 개월 수를 그대로 비교한다. DAYS_FROM_REFERENCE는
공고 기준일부터 계약 종료일까지의 일수를 포함 범위와 비교한다. 종료일이 없는 것으로 입력한
NOT_APPLICABLE은 종료일 범위를 만족하지 않는 것으로 읽으며 빈 날짜 입력을 다시 요구하지 않는다.
거주지역의 행정구역 범위가 조건보다 넓으면 `comparedValues.reason=REGION_PRECISION`과
입력한 지역·필요 지역을 반환한다. 이는 빈 입력과 달리 필요한 지역 세부값이 아직 없다는 뜻으로,
현재 거주지역을 더 구체적으로 입력하도록 안내한다. 공고별 과거 거주 이력을 묻는 것이 아니다.
필요한 정밀도의 값이 있으면 현재 입력으로 충족·미충족을 결정한다.
차량 없음이 확인되면 차량가액 상한 조건은 충족한다. 미입력 자산을 0원으로 바꾸지 않는다.

`appliesWhen`은 구현된 비교 규칙을 적용할 수 있는 범위다. 적용 조건에 필요한 수집값이 비었으면
그 입력을 요청한다. 적용 조건이 미충족이거나 직접 확인이 필요하면 fallback 안내로 넘긴다.
원문 면제를 뜻하는 일반 규칙으로 해석하지 않는다.
면제·대안이 입력으로 판정 가능하다면 원문 관계에 맞게 ALL/ANY로 구조화한다.

## 검색과 관심 알림의 연결 규칙

전체 공고 목록의 신청자격 필터와 이 기능의 관심 알림 연결은 후속이다. 다음은 연결 시 지켜야 할
동작이며, 현재 단건 API의 지역별 지원 가능 선택지 조회만으로 전체 검색·발송이 구현된 것은 아니다.

필터는 **지역 · 주택유형 · 공급유형 · 지원 가능한 공고 · 특수조건(한부모가정 등)**으로 구성한다.
기본 목록은 개인에게 지원 가능한 공고만 보여주는 목록이 아니다.

| 선택 | 포함 기준 |
|---|---|
| 공고 정보 필터만 선택 | 공고 정보로 좁힌다. 개인 미충족·정보 부족·직접 확인 필요를 이유로 숨기지 않음 |
| 지원 가능한 공고 선택 | 개인 판정상 지원 가능한 공고만 포함 |
| 지역 + 지원 가능한 공고 | 선택 지역 안에 지원 가능한 실제 모집 선택지가 하나 이상 있어야 포함 |

지역 선택은 공고 전체 개인 판정을 바꾸지 않는다. `eligibleOptionIdsInRegion`으로 해당 지역 안의
신청 단위·신청자격·근거를 함께 연결한다. 한부모가족 필터는 지원대상 한부모가족 경로가 있는 공고도
찾되, 필터를 선택한 사용자가 해당 법정 자격을 가진 것으로 프로필에 기록하지 않는다.

관심 알림은 검색 선택과 별도로 신청한다. 새 공고가 수집·구조화되어 저장되면 등록한 관심 조건과
수신 설정으로 대상을 정한다. 관심 조건만 신청할 수도 있고 `지원 가능한 공고만`을 추가할 수도 있다.
후자는 관심 지역 안의 지원 가능한 실제 선택지가 있어야 한다. 개인 판정을 제한하지 않는 관심 알림을
“신청할 수 있는 공고”로 표현하지 않는다. 수지구 알림과 수지구+지원 가능 알림은 서로 다른 구독 조건이다.

공고 조건 정정 시 이전 기준의 안내는 보류하고 새 기준으로 확인한다. 전체 취소는 지원 가능 노출과
대기 발송을 중단하고, 일부 취소는 해당 선택지에만 반영한다. 현재 백엔드는 결과·발견의 현재성을
검사하며 실제 발송·이미 전달한 안내의 변경 통지는 [후속 범위](overview.md#아직-결정하지-않은-사항)다.

## 공고 사례와 재현 fixture

D01·D05·D11은 아래 fixture를 구별하는 사례 ID이며 DB 공고 ID나 완전한 원문 데이터가 아니다.
공고의 일부 조건과 가상 사용자로 판정 동작을 재현한다. fixture의 `https://example.test/Dxx.pdf`는
시험용 주소이며 실제 원문 다운로드 링크가 아니다. 원문의 전체 자격·일정·물량과 실제 현재 모집 여부는
이 예제만으로 확인할 수 없다.

원본은 [EligibilityFixtures](../../../backend/src/test/java/com/toadzip/backend/eligibility/EligibilityFixtures.java)의
`d05()`, `d11()`, `d01()`과 공통 가상 사용자·기초 자료인
[BasicComparisonFixtures](../../../backend/src/test/java/com/toadzip/backend/eligibility/BasicComparisonFixtures.java)다.
기대 결과는 [EligibilityEvaluatorTest](../../../backend/src/test/java/com/toadzip/backend/eligibility/EligibilityEvaluatorTest.java)에서 확인한다.

| 사례·공고명 | 구조화·사용자 가정 | 확인할 결과와 한계 |
|---|---|---|
| D05 사천벌리1 | 기준일 2026-10-16, 26형 × 일반입주자 신청자격. 사용자 B는 1989-01-10생, 사천 거주, 본인 포함 가족 2명, 자녀·태아 없음, 부부·혼인신고일 2018-04-01. 본인 소득 월 200만원·세대 월 300만원, 가족 총자산 1억원, 가족 무주택·무차량 | 입력된 예제 조건은 충족. 원문 차목은 항목명이며 화면명은 일반입주자 신청자격. 신청 제한 이력은 별도 안내 |
| D11 SH 영구임대 | 기준일 2025-11-13, 서울 거주·가족 무주택이 공통 조건. 수급자·보훈대상자·지원대상 한부모가족을 별도 필수 자격 경로로 작성. 지연은 한부모 가족 형태라고 가정 | 서울 거주라도 법정 자격은 직접 확인. 부산 거주로 바꾸면 공통 미충족 우선. fixture의 자격명·연결을 검증하며 legalBasis는 null, conditionsAsWritten은 예시 문구이므로 법정 요건 전체 검증이 아님 |
| D01 경기도 경기행복주택 | 기준일 2026-10-16, 수지구 용인죽전 36A 신혼부부와 기흥구 용인영덕 17A1 청년. 수진은 1997-01-10생 미혼. 예제의 신혼 경로는 가족 형태만, 청년 경로는 미혼·19~39세만 비교 | 기흥구 선택지만 ELIGIBLE, 공고 전체는 ELIGIBLE. 실제 공고의 소득·자산·거주 조건 전체는 담지 않아 실제 전체 자격 충족의 증거가 아님 |

사천벌리1의 동일 사용자에서 다음 값을 바꾸면 누락과 미충족을 구분할 수 있다.

| 변경 | 기대 결과 |
|---|---|
| 세대 소득 미입력 | 필요한 세대 소득 입력을 요청. 미수집 신청 제한은 별도 안내 |
| 세대 소득 미입력 + 가족 총자산 3억원 | 일반입주자 INELIGIBLE 우선. 자산 기준 2억 4,500만원 이하·입력 3억원을 표시하고 소득 조건도 확인할 수 있다고 안내 |
| 다른 값 유지, 세대 소득 3,519,762원 / 3,519,763원 | 예제 2인 소득 상한과 같으면 충족, 1원 초과면 미충족. 표·연도·비율은 fixture가 참조한 기준이며 모든 공고 공통 상한이 아님 |

D11에서 부산 거주 지연이 한부모가족 필터만 선택하면 공고는 검색 대상이고 개인 미충족은 유지된다.
지원 가능 필터를 추가하면 제외한다. D01의 수진은 수지구 단독 검색에는 포함하고 수지구+지원 가능에는
제외하되, 기흥구 선택지가 있으므로 공고 전체 개인 판정은 지원 가능으로 유지한다. 이 두 검색 시나리오는
위 연결 규칙의 인수 기준이며 현재 프론트 전체 흐름을 검증했다는 뜻은 아니다.

## 코드와 API 검증 진입점

[EligibilityEvaluator](../../../backend/src/main/java/com/toadzip/backend/eligibility/domain/EligibilityEvaluator.java)는
공통 조건·경로·선택지와 공고 집계를 소유하고,
[EligibilityController](../../../backend/src/main/java/com/toadzip/backend/eligibility/controller/EligibilityController.java)는
상세·처리·발견 조회와 확인 API의 진입점이다. 현재성·원자적 갱신은 [저장 설계](storage.md)를 따른다.

[판정 API 테스트](../../../backend/src/test/java/com/toadzip/backend/eligibility/controller/EligibilityDecisionIntegrationTest.java)와
[재판정 API 테스트](../../../backend/src/test/java/com/toadzip/backend/eligibility/controller/EligibilityRecheckIntegrationTest.java)는
응답·지역 선택지·최신성·발견 확인을 다룬다. 실행 조건과 증거의 범위는 [검증 안내](overview.md#검증-범위)를 따른다.

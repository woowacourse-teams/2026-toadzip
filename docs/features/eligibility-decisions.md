# 신청자격 판정 API와 표시 계약

입력한 공통 사용자 정보로 **어떤 실제 모집 선택지에 지원할 수 있는지** 판정한다.
선택지는 신청 단위 × 신청자격 경로다. 당첨·계약·입주 확정을 뜻하지 않는다.
공고 저장 구조는 [데이터 모델](eligibility-storage.md), 입력 계약은
[프로필·입력 카탈로그](eligibility.md#현재-받는-정보)를 따른다.

## 단건 조회

`GET /api/v2/announcements/{announcementId}/eligibility?region=41465`

회원 인증이 필요하고 `Cache-Control: no-store`를 반환한다. 회원 ID는 인증 세션에서 가져온다.
`region`은 선택한 탐색 지역이며 생략할 수 있다. 지역 선택을 프로필이나 알림 설정에 저장하지 않는다.

| 응답 | 의미 |
|---|---|
| requestedAnnouncementId / announcementId | 요청 ID와 정정 체인의 실제 최신 공고 ID |
| availability | AVAILABLE / NOT_STRUCTURED / NOT_PUBLISHED / CANCELLED |
| ruleSetId / profileVersion | 판정에 사용한 공개 내용 ID와 현재 사용자 입력 수정 식별값 |
| result | 아래의 개인 판정. 공개된 구조가 없거나 공고 전체가 취소·비공개이면 null |
| content | 판정과 같은 공개본의 구조화 내용. 근거·자격·물량·임대조건·일정은 여기서 연결 |
| selectedRegion / eligibleOptionIdsInRegion | 선택한 지역 안에서 지원 가능한 실제 선택지 ID. 지역 생략 시 전체 지원 가능 선택지 |

이 PR의 조회는 동일 DB 스냅샷으로 계산한다. 다음 최신 결과 PR에서 저장·재판정 상태를 연결한다.
기존 `/basic-comparison`과 `/eligibility/summary`는 이전 **기본 비교** 계약이며 최종 지원 가능
여부나 건수로 사용하지 않는다. 새 화면은 이 문서의 판정 API를 사용한다.

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
| options[].pathId / pathLabel | 신청자격 경로. D05의 화면명은 일반입주자 신청자격이며 원문 차목은 순위가 아님 |
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

D05 자산 3억원·소득 미입력은 INELIGIBLE을 먼저 보여준다. 자산 항목에 `LTE`,
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

`appliesWhen`은 구현된 비교 규칙을 적용할 수 있는 범위다. 범위가 맞지 않거나 판단할 수
없으면 연결된 직접 확인 안내로 넘긴다. 원문 면제를 뜻하는 일반 규칙으로 해석하지 않는다.
면제·대안이 입력으로 판정 가능하다면 원문 관계에 맞게 ALL/ANY로 구조화한다.

## 지역과 전체 판정

D01에서 기흥구 용인영덕 17A1 청년 경로에 지원 가능하면 공고 전체는 ELIGIBLE이다.
수지구를 선택하면 eligibleOptionIdsInRegion은 빈 배열이고, 기흥구를 선택하면 해당 선택지가
반환된다. 검색의 지역+지원 가능 조건은 이 범위를 사용해야 한다. 이 API를 추가한 것만으로
전체 목록 필터나 알림이 구현된 것은 아니다.

테스트의 D05·D11·D01은 실제 공고 일부와 가상 사용자를 결합한 검증 자료다. 실제 공고 전체
적재·정정 수집·현재 접수 여부를 검증한 결과와 구분한다.

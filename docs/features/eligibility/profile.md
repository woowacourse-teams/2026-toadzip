# 공통 사용자 정보와 입력 API

현재 사용자 정보를 여러 공고의 판정에 재사용하는 계약이다. 입력값의 의미·상태·검증은 이 문서를 따른다.
조건을 어떻게 판정하는지는 [판정과 표시](decisions.md), 물리 저장과 버전 일관성은 [저장 설계](storage.md)를 따른다.

## API

모든 회원 경로는 `USER` 세션 인증이 필요하다. 사용자 ID는 세션에서만 얻는다.
응답은 `{"data": ...}`이며 `Cache-Control: no-store`를 붙인다. 쓰기에는 기존 CSRF 토큰이 필요하다.

| 메서드·경로 | 동작 |
|---|---|
| `GET /api/v2/eligibility/input-catalog` | 현재 입력 25개, 타입·단위·분기·선택 여부·값 종류와 설명 |
| `GET /api/v2/me/eligibility-profile` | 현재 프로필. 없으면 version 0과 UNKNOWN 값 |
| `PUT /api/v2/me/eligibility-profile` | 기대 버전을 확인하고 현재 프로필 전체 교체 |

### 현재 받는 정보

| 흐름 | 입력 |
|---|---|
| 본인 | 생년월일, 거주지역, 현재 지역 거주 완료 개월 수, 현재 거주 집 계약 종료일, 대학생·취업준비생 여부, 주택·자동차 보유와 차량가액, 자산, 세전 월평균소득 |
| 가족·세대 | 가족 형태, 본인 포함 등본상 가족 수, 세대 합산 세전 월평균소득 |
| 미혼 | 세대분리 여부. 세대주 여부로 해석하지 않음 |
| 한부모·부부/예비부부 | 임신·자녀 여부, 태아 포함 자녀 수, 막내 생년월일 또는 출산예정일 |
| 부부/예비부부 | 혼인신고일 또는 예정일, 맞벌이 여부 |
| 선택 가족정보 | 본인 포함 가족의 주택·자동차 보유, 최고 차량가액, 총자산 |
| 부모 소득 예외 | 미혼 대학생이 세대분리했고 본인 소득이 0이라고 입력한 경우의 부모 소득 합계 |

코드·타입 원본은 [ProfileField](../../../backend/src/main/java/com/toadzip/backend/eligibility/domain/ProfileField.java)다.
학교/근무지, 청약통장, 소득활동 이력, 우대·복지 신분은 현재 입력에서 제외한다.
공고에 필요하면 직접 확인 안내에 남긴다.

프로필 저장은 **전체 교체**다. 아래는 선택 가족정보를 생략한 부분 입력 예시다. 보내지 않은 사실은
UNKNOWN으로 바뀌며, 이 예시만으로 신청자격이 충족되는 것은 아니다.

```json
{
  "expectedVersion": 0,
  "inputContractVersion": 3,
  "profile": {
    "familyDetailsState": "SKIPPED",
    "facts": {
      "FAMILY_TYPE": {"state": "PROVIDED", "value": "COUPLE_OR_ENGAGED"},
      "HOUSEHOLD_MONTHLY_INCOME": {"state": "PROVIDED", "value": 3000000},
      "MARRIAGE_EVENT": {
        "state": "PROVIDED",
        "value": {"kind": "REGISTERED", "date": "2018-04-01"}
      }
    }
  }
}
```

조회 응답은 `version`, `inputContractVersion`, `updatedAt`, `profile`, `reevaluationState`를 반환한다.
저장 성공 시 version이 1 증가하고 reevaluationState는 PENDING이다. 이는 입력 저장 완료이며
재판정 완료는 [처리 상태·최신 판정 API](decisions.md#저장-완료재판정-완료새-지원-가능-안내)에서 확인한다.
최초 저장은 expectedVersion 0, 이후에는 조회한 현재 버전을 사용한다. 동시 수정 중 하나만
성공하며 다른 요청은 `409 BASIC_COMPARISON_CONFLICT`다. 다른 회원의 데이터와 섞지 않는다.

- 값 상태는 `PROVIDED / UNKNOWN / NOT_APPLICABLE`이다. PROVIDED에만 값이 있어야 한다.
- 가족정보 상태는 `NOT_STARTED / PROVIDED / SKIPPED`다. 생략 상태에 가족 제공 값을 함께 보내지 않는다.
- 지역 거주기간은 현재 거주지역 코드 범위에서 계속 거주한 완료 개월 수(0~1800)이며 현재 집 거주기간과 구분한다. 자동으로 기간을 늘리거나 과거 지역을 묻지 않는다. 지역 변경 시 함께 갱신한다.
- 현재 집 계약 종료일은 미래 날짜도 유효하다. 종료일이 없는 거주는 NOT_APPLICABLE, 모르면 UNKNOWN이다. 해당 없음 자체를 특정 조건의 충족으로 추정하지 않는다.
- 새 저장 요청은 입력 계약 3을 사용한다. 저장된 계약 2는 읽되 새 항목을 UNKNOWN으로 정규화한다. 새 저장 때 계약 3으로 교체하며 별도 과거 프로필을 만들지 않는다.
- 날짜는 ISO 날짜, 금액은 음수 없는 정수 원이다. 소득은 원/월이며 본인과 세대 합계를 구분한다.
- 서비스 입력 대상은 만 19세 이상이다. 실제 생년월일·신고일은 미래일 수 없다. 공고의 연령 비교는 공고 기준일을 쓴다.
- 혼인 날짜의 종류는 `REGISTERED / PLANNED`, 막내 날짜는 `BORN / EXPECTED`다. 예정일이 지나도 실제 사건으로 바꾸지 않는다.
- 등본상 가족 수는 본인 포함·태아 제외 1~100명, 자녀 수는 태아 포함 0~100명이다. 법정 세대원 수와 같다고 추정하지 않는다.
- 자동차 없음이면 가액은 해당 없음으로 표현한다. 모르는 자산·부모 소득을 0원으로 바꾸지 않는다.
- 본인 포함 보유 정보와 최고 차량가액의 모순은 거부한다. 자산 산정 내역은 받지 않으므로 가족 총자산과 본인 자산의 대소만으로 값을 고치거나 거부하지 않는다.
- 활성 흐름 밖의 제공 값, 알 수 없는 필드·지역, 구 특수 신분과 잘못된 계약 버전·수치 타입은 400이다.


## 변경할 때 확인할 경계

새 필드를 추가하면 입력 카탈로그·활성 흐름·상태 검증·입력 계약 버전과 해당 조건 평가를 함께 검토한다.
수집하기로 한 항목에 필요한 값이 있으면 그 값을 공고 기준과 비교한다. 입력했다는 사실만으로 충족시키거나,
과거 이력을 추가 확인해야 한다는 이유로 공고별 질문을 만들어 계속 보류하지 않는다.
조건과 무관한 빈 항목을 모두 채우도록 요구하지 않는다. 수급자·지원대상 한부모가족처럼 수집하지 않는
필수 자격은 입력 필드로 추정하지 않고 [관리자 자격 구조](authoring.md#조건과-필수-자격신청-제한의-구분)에 보존한다.

프로필 인증·CSRF·회원 격리·전체 교체·버전 충돌은
[프로필 API 테스트](../../../backend/src/test/java/com/toadzip/backend/eligibility/controller/EligibilityProfileIntegrationTest.java),
거주 입력과 계약 호환은
[거주 정보 테스트](../../../backend/src/test/java/com/toadzip/backend/eligibility/controller/ResidenceProfileIntegrationTest.java)를 기준으로 확인한다.

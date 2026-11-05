# 사용자와 공고의 자격 비교

공식 공고의 조건을 구조화한 현재 공개본과 회원의 현재 입력을 비교한다.
비교 단위는 **신청 단위 × 자격 경로**인 신청 선택지다. 한 경로의 미충족이나 보류는
다른 경로의 결과를 지우지 않는다. 결과는 신청 자격을 확인하는 보조 정보이며 당첨과 입주를 보장하지 않는다.

이 문서는 백엔드 계약을 다룬다. 화면, 알림 발송, 개인정보 동의 절차는 별도 작업이다.

## 회원 API

모든 경로는 `USER` 세션 인증이 필요하다. 사용자 식별자는 세션에서만 읽는다.
응답은 `{"data": ...}`이며 개인화 응답에는 `Cache-Control: no-store`를 붙인다.
쓰기 요청은 기존 인증 API와 같은 CSRF 토큰이 필요하다.

| 메서드와 경로 | 동작 |
|---|---|
| `GET /api/v1/me/eligibility-profile` | 현재 입력 조회. 프로필이 없으면 version 0과 모든 항목 UNKNOWN 반환 |
| `PUT /api/v1/me/eligibility-profile` | 기대 버전을 확인하고 전체 현재 입력 교체 |
| `GET /api/v1/eligibility/input-catalog` | 입력·파생 사실, 의미상의 한계, 목록 공백 및 해당 없음 허용 항목 조회 |
| `GET /api/v1/announcements/{id}/eligibility` | 정정 체인의 마지막 게시물에 대한 결과와 원문 근거 조회 |
| `GET /api/v1/eligibility/summary` | 접수 중 공고의 N/M과 입력 안내 집계 |

프로필 저장 형식은 다음과 같다. 아래는 **나머지 항목을 모두 UNKNOWN으로 만드는 신규 입력 예시**다.
일부 항목을 수정할 때는 조회한 `facts` 전체에서 해당 값만 바꿔 보낸다.

```json
{
  "expectedVersion": 0,
  "facts": {
    "BIRTH_DATE": {"state": "PROVIDED", "value": "1994-05-20"},
    "HOUSEHOLD_ASSETS": {"state": "UNKNOWN", "value": null}
  }
}
```

- 상태는 `PROVIDED`, `UNKNOWN`, `NOT_APPLICABLE`이다. PROVIDED에만 값이 있어야 한다.
- 날짜는 ISO 날짜, 금액은 원 단위 정수, 기간은 개월, 지역은 카탈로그에 등록된 2·5·10자리 코드다.
- 자동차가 없으면 해당 자동차가액은 NOT_APPLICABLE이다. 자녀가 없으면 자녀 수와 막내 생년월일도
  NOT_APPLICABLE이다. 청약통장 세 항목은 모두 해당 없음이거나 모두 해당 없음이 아니어야 한다.
- 모름을 0이나 false로 바꾸지 않는다. 해당 없음인 자동차가액·부모 소득은 비교 시 0원으로 해석한다.
  해당 없는 혼인기간·자녀 나이를 요구하는 조건은 미충족이다.
- 생년월일과 막내 생년월일은 미래일 수 없다. 미혼이면 혼인신고일은 해당 없음이다.
- 성공할 때 version을 1 증가시킨다. 특수 상태 4종도 같은 트랜잭션과 version에 포함된다.
  오래된 version은 `409 ELIGIBILITY_CONFLICT`다. 일부 입력만 저장되는 경우는 없다.
- 35개 입력 코드 밖의 사실을 받지 않는다. 세대주 여부나 불법전대 이력의 답을 저장하는 필드는 없다.

입력 카탈로그의 `facts`는 `code`, `label`, `factKind`, `valueType`, `subjectLevel`,
`meaningStatus`, `meaningNote`, `dependsOn`을 반환한다. `dependsOn`은 사실 코드의 JSON 배열이며
입력 항목에서는 빈 배열이다. `inputGaps`는 `code`, `label`, `neededFact`, `subjectDescription`,
`whyNotComputable`, `followUpQuestion`을 반환한다.

## 결과와 근거

상세 응답에는 요청한 게시물 ID, 실제 비교한 마지막 게시물 ID, 공개본 ID/version,
프로필 version, 출처 메타데이터와 결과가 들어간다. 기준일은 공개본의 `eligibilityReferenceDate`이며
접속한 날짜에 따라 나이·혼인기간을 다시 계산하지 않는다.

`availability`는 `AVAILABLE`, `NOT_STRUCTURED`, `CANCELLED`, `NOT_PUBLISHED`다.
AVAILABLE일 때만 `result`가 있다. 존재하지 않는 게시물은 404다.

| 선택지 결과 | 뜻 |
|---|---|
| `ELIGIBLE` | 판정한 조건 충족 |
| `ELIGIBLE_WITH_OBLIGATIONS` | 현재 조건 충족, 입주 전 이행 필요 |
| `UNDETERMINED` | 입력·목록 공백·해석·기관 심사 등의 이유로 보류 |
| `NOT_ELIGIBLE` | 필수 조건 미충족 |
| `NOT_RECRUITING` | 이번 모집에서 제외된 경로 |
| `NOT_FOR_INDIVIDUAL` | 기관 등 개인 신청 범위 밖 |

`blockingConditionIds`는 실제 보류를 만드는 조건만 가리킨다. 충족된 ANY 묶음의 다른 보류 조건은
막은 조건에 넣지 않는다. 목록 밖 배제 조건만 남은 경우 `undeterminedKind=CONFIRMATION_ONLY`이며,
결과 코드는 계속 UNDETERMINED다. 이때 “판정 가능한 조건 모두 충족 · 확인 사항 n건”으로 표시할 수 있다.
그 밖의 보류는 OPEN이다. 모집 여부 자체가 UNCLEAR인 경우에는 `PENDING_DEFINITION`을 추가한다.
이 경우 자격 조건이 모두 충족되었다면 막은 조건 ID 목록은 비어 있을 수 있다.

조건 결과에는 사유, 입력할 사실 코드, 표시할 금액·파생값, 원문 요지, 검사 대상, 유지 기간과 근거 ID가 있다.
근거 ID로 원문 쪽 범위·발췌를 찾고 문서 ID로 파일명·해시·출처 수준·수집 시각을 찾는다.
구조화가 PARTIAL이면 미완료 한계를 함께 반환한다.

공고 요약은 다음 순서에서 가장 앞선 결과를 고른다.

`HAS_ELIGIBLE → HAS_CONDITIONAL → HAS_CONFIRMATION_ONLY → UNDETERMINED_ONLY → NONE_ELIGIBLE`

기관 모집은 `OUT_OF_SCOPE`다. 순위, 배점, 임대조건과 물량 진입 조건은 자격과 따로 계산한다.
여러 순위·배점 단계는 `selectionResults`에서 각 단계의 범위를 확인한다. 같은 종류의 결과를 하나로
요약할 수 있을 때만 선택지의 `rankTierMin/Max`, `scoreMin/Max`를 제공한다.
추첨·선착순·기관 통보 순서는 실제 당첨 순서로 계산하지 않는다.
물량 비율의 모수가 미확정이면 `variabilityNote`를 유지하며 모집 인원으로 환산하지 않는다.

## 평가 규칙

| 상황 | 처리 |
|---|---|
| ALL 중 필수 조건 미충족 | 다른 조건이 보류여도 미충족 |
| ANY 중 조건 충족 | 다른 조건의 보류가 결과를 막지 않음 |
| 본인 또는 세대 검사 대상이 불명확 | 두 후보를 계산. 결과가 다르면 SUBJECT_AMBIGUOUS와 후보별 결과 반환 |
| 가족구성원 수 1 | 세대주 여부를 추정하지 않음 |
| 본인 후보의 소득 기준 | 본인 소득과 가구원수 1을 기준표·가산 발동에 함께 적용 |
| 미래 혼인 세대·배우자의 입력 없음 | 현재 본인·세대 값으로 대체하지 않고 목록 공백으로 보류 |
| 면제 조건 보류 | 기본 조건 충족은 유지. 기본 조건 미충족이면 면제에 따라 달라지므로 보류 |
| 가산 발동 보류 | 가능한 한도 전체에서 결과가 같을 때만 확정 |
| 동시에 여러 대체 한도 발동 | UNINTERPRETED로 보류. 작성자는 발동 조건을 상호 배타적으로 구조화 |
| 입력 의미가 더 넓음 | 불리언 false는 판정, true는 MEANING_UNSETTLED |
| 입력 의미가 더 좁음 | 불리언 true는 판정, false는 MEANING_UNSETTLED |
| 기관 판단·수동 처리·해석 미완료 | 별도 사유로 보류 |
| 기준표에 없는 가구원수 | 추가 1인 금액이 명시된 표에서만 외삽 |

도시근로자 기준표의 비율 금액은 원 단위 반올림한다. 다른 기준표를 추가할 때도 원문 금액과 대조해야 한다.
2023-03-28 이후 출생 자녀 수를 확정할 수 없는 입력은 가능한 수의 구간으로 계산한다.
지역 포함 판정은 기존 카탈로그의 구 코드 대응과 하위 지역을 이용한다. 화성시 41590과 동탄구 41597을
문자열 접두사만으로 비교하지 않는다. 시도만 입력해 하위 시군구를 알 수 없으면 추가 입력으로 안내한다.

## 관리자 초안과 공개

경로 기준은 `/api/admin/announcements/{id}/eligibility-rule-set/draft`다. ADMIN 세션과 쓰기 CSRF가 필요하다.
한 게시물에는 공개본과 초안을 각각 최대 한 개 둔다.

| 메서드와 상대 경로 | 요청과 결과 |
|---|---|
| `GET` | 현재 초안. 없으면 data=null |
| `PUT` | `{expectedDraftId, expectedRevision, content}`. 신규는 ID=null, revision=0 |
| `POST /copy` | 현재 공개본을 새 초안으로 복사. 이미 초안이 있으면 409 |
| `POST /validation` | `{draftId, expectedRevision}`. 공개 검증 성공 시 204 |
| `POST /publish` | 같은 버전 요청을 검증하고 공개. 성공 시 현재 공개본 반환 |

`content`는 메타데이터와 문서·근거·공급·조건·선정·임대·일정·규칙의 배열이다.
필드 정의는 [RuleSetContent](../../backend/src/main/java/com/toadzip/backend/eligibility/domain/RuleSetContent.java)와
[RuleSetData](../../backend/src/main/java/com/toadzip/backend/eligibility/domain/RuleSetData.java)에 있다.
배열에 없는 행은 전체 교체에서 삭제된다. 입력 행의 ID는 같은 content 안에서 연결하는 양수 식별자이며,
저장 시 DB가 새 식별자를 배정하고 내부 참조를 함께 바꾼다. 다음 수정에는 응답의 content와 revision을 사용한다.
공유 기준표와 기존 공고·첨부·단지의 ID는 실제 DB 식별자를 사용한다.

초안 저장은 형식, 카탈로그 참조와 DB 무결성을 확인한다. 공개 검증은 추가로 원문 쪽 범위,
조건·면제·가산과 물량의 순환 참조, 조건 묶음의 목적, 공급 연결, 선정 단계와 임대조건을 확인한다.
행 수는 20,000개, JSON은 4 MiB, 평가 참조 깊이는 공개 검증에서 64로 제한한다.

공개는 정정 체인과 초안을 잠근 한 트랜잭션에서 수행한다. 검증 및 형식 번호 1의 스냅숏 생성에 성공한 뒤
같은 게시물과 이전 정정 체인의 공개본을 삭제하고 초안을 공개본으로 바꾼다.
검증 실패 시 옛 공개본을 유지한다. 동일 초안의 동시 공개·오래된 초안 저장은 409다.
미공개·삭제·취소 게시물과 정정 체인의 과거 게시물에는 공개할 수 없다.

공개본은 직접 수정하지 않는다. 수정할 때 복사한 초안을 다시 공개한다. 같은 게시물의 공개 version은
1씩 증가하며, 초안 저장 revision과는 별개다. 새 정정 게시물의 첫 공개 version은 1이다.
원천 첨부·단지 연결이 삭제된 뒤 복사할 때는 현재 정규화 행을 읽어 해제된 연결을 유지한다.
공개 당시 스냅숏에 남은 외부 식별자를 새 초안에 다시 연결하지 않는다.

## 접수 중 집계와 저장 정책

- N(`qualifiedAnnouncementCount`): HAS_ELIGIBLE, HAS_CONDITIONAL, HAS_CONFIRMATION_ONLY인 공고 수.
- M(`additionalInputAnnouncementCount`): N에 포함되지 않고, 입력 누락과 확인 사항만 해결하면
  판정할 수 있는 선택지가 하나라도 있는 공고 수.
- `singleInputCounts`: 입력 하나만 필요한 선택지가 있는 공고를 입력 코드별로 센다.
  입력 후 충족할 것을 뜻하지 않고 지원 여부를 확인할 수 있다는 뜻이다.

선택지 수와 관계없이 한 공고는 N 또는 M에서 한 번만 센다. 공개 판본이 없는 공고, 미공개·삭제·취소 공고,
정정 체인의 이전 게시물은 제외한다. 기준 날짜는 Asia/Seoul이다.
접수 중 여부는 기존 [공고 일정 SQL](../../backend/src/main/java/com/toadzip/backend/announcement/repository/ApplicationScheduleSql.java)을 재사용한다.
일정 검수가 끝났으면 현재 날짜의 CONFIRMED 일정만 포함하고 CONDITIONAL은 제외한다.
검수 전이면 공고의 접수 시작일~종료일을 사용한다. 사용자 순위별 접수 가능 여부는 후속 정책이다.

과거 판본·프로필, 평가 결과는 저장하지 않는다. 회원 삭제 시 프로필과 특수 입력은 FK로 함께 삭제한다.
원천 단지용 `announcement_complexes`와 판본 소속 `eligibility_rule_set_complexes`는 수명을 분리한다.
원천 첨부·단지 연결이 없어져도 판본의 파일 해시와 근거는 남는다.

공개 스냅숏만 `(id, version)`으로 캐시하며 최대 64개·직렬화 크기 16 MiB로 제한한다.
개인화 결과는 캐시하지 않는다. 집계는 프로필과 공개본을 같은 읽기 스냅숏에서 보고,
접수 중 공고를 ID 순서로 32개씩 읽는다. 공용 접수 목록 캐시는 기존 검수·정정·취소 변경의
무효화 규칙과 실제 부하를 확인한 뒤 추가한다.

## 검증과 마이그레이션

Docker를 실행한 뒤 backend에서 다음을 실행한다. 테스트용 PostgreSQL은 Testcontainers가 만들고 정리한다.

```sh
./gradlew test --tests 'com.toadzip.backend.eligibility.*'
./gradlew test --rerun check --build-cache
```

인수 자료는 D01 동탄호수공원과 D05 사천벌리1의 부분 구조화와 가상 사용자 A/B/C다.
파일 해시는 테스트용 값이며 실제 PDF를 동봉하지 않는다. 전체 공고나 모든 자격 조합을 검증한 자료로
해석하면 안 된다. B의 자산 미입력→379,000,000원→1원 초과, A의 본인·세대 분기,
C의 24개 선택지와 71~86점 범위, 금액 경계, 공개 교체·실패·경합, 프로필 경합, 보안 필터를 검증한다.

새 마이그레이션은 테이블·기준 데이터만 추가하며 기존 사용자 사실을 이전하거나 실제 공고를 공개하지 않는다.
DB 마이그레이션 후 새 애플리케이션을 기동한다. 이전 앱은 추가 테이블을 사용하지 않으므로 함께 존재할 수 있다.
앱 버전을 되돌려도 적용된 Flyway는 삭제되지 않는다. 잘못 공개한 과거 판본으로 즉시 복구할 이력은 없으므로
원문을 다시 검토해 초안으로 재공개해야 한다. 실제 DB 적용과 배포는 별도 운영 승인 대상이다.

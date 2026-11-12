# 공고 구조화와 관리자 공개 계약

관리자는 원문에서 신청 단위·신청자격·실제 모집 조합을 구분해 초안을 작성하고 검토 후 공개한다.
공통 사용자 입력이 적더라도 공고의 정확한 필수 자격과 조건을 잘라내지 않는다.
이 문서는 작성 내용과 공개 검증의 원본이다. 물리 저장은 [데이터 모델](storage.md),
사용자 결과는 [판정 계약](decisions.md)을 따른다.

## 현재 신청자격 판정용 작성

신청자격 판정 대상인 개인 공고는 `metadata.applicantKind=INDIVIDUAL`,
`metadata.accessMode=APPLICATION_ROUTES`로 작성한다. `paths`는 하나 이상이고 모두
`kind=APPLICATION`이어야 한다. 각 경로에는 공통 조건, 경로 조건, 필수 자격 연결 중 적어도 하나가 필요하다.
아무 조건도 구조화하지 않은 경로를 지원 가능으로 만들지 않는다. 공고 전체나 그 경로에 필수 직접 확인
(`REQUIRED_QUALIFICATION`) 안내가 걸린 경로는 조건이 없어도 저장할 수 있다. 직접 확인이 있어 판단 보류로만 나오기 때문이다
(관리자가 경로의 마지막 조건까지 '직접 확인으로 바꾸기'를 한 경우 등).

저장 형식 `schemaVersion=3`과 `APPLICATION_ROUTES`는 서로 다른 구분이다. 버전 3으로 저장해도
이전 `GENERAL`/`COMMON_ONLY` 경로일 수 있다. 이전 조합의 작성·공개·읽기도 허용하지만
최신 판정으로 자동 승격하지 않는다. 조합과 소비 범위는 [호환 계약](compatibility.md#구조와-저장-버전의-호환)을 따른다.
기관이 신청·계약하는 `ORGANIZATION` 공고에는 개인 `paths`와 `options`를 만들지 않는다.

## 구조화 내용

내용의 필드와 자료형을 정의한 원본은
[EligibilityContent](../../../backend/src/main/java/com/toadzip/backend/eligibility/domain/EligibilityContent.java)다.
다음 항목을 하나의 작성 단위 안에서 연결한다.

| 항목 | 데이터와 관계 |
|---|---|
| metadata | referenceDate(공고 기준일), applicantKind, accessMode, reviewStatus, classificationEvidenceId, scopeNote(검토·구조화 범위) |
| documents / evidences | 파일 ID·이름·URL·해시·전체 쪽수와 근거의 문서 ID·쪽 범위·위치·설명 |
| supplyUnits | ID·명칭·regionCodes와 실제 단지·주택형 목록. 여러 단지·형의 묶음 신청 유지 |
| commonChecks | 모든 실제 신청 경로에 공통으로 적용되는 조건. 일부 단위·경로 조건은 여기에 올리지 않음 |
| paths | ID·표시명·kind·checks·qualificationIds. 다른 조건이나 자격을 요구하면 원문 관계에 맞게 경로 분리 |
| options | ID·supplyUnitId·pathId·recruitmentStatus. 실제 모집 조합만 저장하고 같은 단위×경로는 한 번만 존재 |
| qualifications | ID·exactName·legalBasis·conditionsAsWritten·categories·evidenceIds. 정확한 자격과 탐색 분류를 분리 |
| guidances | ID·kind·title·body·items·targets·evidenceIds. 미수집 신청 제한·절차·직접 확인 사항 |
| quantities | 물량 종류·값·공유하는 supplyUnitIds/optionIds·근거. 같은 물량을 경로마다 복제하지 않음 |
| rentOffers | 대상 설명, 보증금·월세 범위와 짝 여부, 전환 조건, supplyUnitIds/optionIds·근거 |
| schedules | 대상명 원문·시작/종료 시각·장소·URL·supplyUnitIds/optionIds·근거 |

ID는 같은 작성 단위 안에서 참조한다. `documents.downloadUrl`은 원문 연결이며 실제 파일을 확인해 적는다.
근거의 쪽수는 문서 쪽 범위 안에 있어야 한다. 물리 주택형의 기존 단지/형 DB ID는 선택적 표시 연결이다.
원천 행이 바뀌었다고 공개 내용의 이름·면적·원문 사실을 조용히 덮어쓰지 않는다.

모집 상태는 `RECRUITING / UPCOMING / NOT_RECRUITING / CANCELLED / UNKNOWN`이다.
모집 여부를 모르면 `UNKNOWN`, 지역을 확인하지 못하면 빈 지역 배열로 남긴다.
다른 신청 단위의 지역이나 사용자의 거주지역을 복사하지 않는다.

물량·임대조건·일정의 적용 대상은 `supplyUnitIds`의 모든 선택지와 `optionIds`의 **합집합**이다.
같은 단위의 특정 경로만 대상으로 삼을 때 `optionIds`를 쓴다. 공유 물량은 ID당 한 번만 세며
원문에 없는 경로별 배분을 만들지 않는다. 임대료 후보와 적용 설명을 보존하되 사용자 임대료를
확정하지 않는다. 순위별 접수 대상명도 원문 문구로 보존한다.

## 조건과 필수 자격·신청 제한의 구분

| 원문의 요구 | 구조화 방법 | 판정에서의 역할 |
|---|---|---|
| 수집하는 사용자 값으로 비교할 조건 | commonChecks 또는 paths[].checks | 조건 기준과 입력을 비교 |
| 미수집 필수 자격 | qualifications에 정확한 명칭·요구 조건·근거, paths[].qualificationIds로 연결 | 해당 자격 직접 확인. 가족 형태나 필터로 충족 추정 금지 |
| 미수집 신청 제한 이력 등 특수 경우 | APPLICATION_EXCLUSION guidance와 적용 범위 | 신청 불가능한 특수 경우로 별도 안내 |
| 적용 범위를 자동 결정할 수 없는 비교(가산 대상 등) | appliesWhen과 fallbackGuidanceId | 적용 조건 밖인 사람은 판정을 막지 않고 연결한 안내를 신청 전 확인으로 보임. 적용 조건을 판단할 수 없으면 직접 확인 |
| 절차·우선공급 기회·다른 경로 설명 | 해당 guidance 종류와 범위 | 설명을 실제 필수 신청 조건으로 바꾸지 않음 |

`qualificationIds`에 여러 자격을 연결하면 그 경로의 요구 자격으로 다룬다. 원문이 대안 자격을
열거했다면 각각의 실제 경로로 표현한다. 법적 근거 `legalBasis`는 nullable이지만 정확한 명칭·
원문 조건·근거를 생략하는 이유로 쓰지 않는다. 넓은 `categories`(예: `SINGLE_PARENT_FAMILY`)는
공고 탐색용이며 법령상 지원대상 한부모가족의 자격 증명이 아니다.

guidance의 대상은 `ANNOUNCEMENT`, `PATH`, `UNIT`, `OPTION`이다. OPTION 대상은 단위와 경로를
함께 지정한다. 일반입주자만의 제한을 공고 전체로 확대하지 않는다. `REQUIRED_QUALIFICATION`
안내도 지원 가능을 확정할 수 없는 직접 확인 사항으로 유지한다.

조건식은 [ProfileRule](../../../backend/src/main/java/com/toadzip/backend/eligibility/domain/ProfileRule.java)의
현재 입력 일치·나이·인원/개월 범위·날짜 경계·지역·금액·소득 상한과 제한된 ALL/ANY를 사용한다.
임의 스크립트나 사용자 속성, 순위·배점·배정 규칙은 받지 않는다. `appliesWhen`과 연산자별
판독 의미는 [조건별 근거](decisions.md#조건별-근거)를 따른다.

소득 조건은 실제 기준표 ID와 비율·가구원수 가산을 참조한다. 100% 금액에 적용 비율을 계산하고
원 단위 HALF_UP으로 반올림한다. 공개 내용에서 참조하는 같은 ID의 기준표는 불변이다.
정정은 새 ID의 표와 이를 참조하는 공개 내용으로 처리하며 잠금 규칙은 [저장 설계](storage.md#최신-결과-구현의-잠금-경계)를 따른다.

## 관리자 API

`ADMIN` 세션 인증과 쓰기 CSRF가 필요하다. 성공 JSON은 `{"data": ...}`이며 204에는 본문이 없다.
기준 경로는 `/api/admin/announcements/{announcementId}/basic-comparison-rule-set/draft`다.
경로에 남아 있는 basic-comparison 이름과 달리 현재 신청자격 작성 계약도 이 API를 사용한다.

| 요청 | 입력·결과 |
|---|---|
| GET 기준 경로 | 현재 초안. 없으면 data null |
| PUT 기준 경로 | expectedDraftId, expectedRevision, content 전체 저장. 최초에는 ID null·revision 0 |
| POST 기준 경로 + `/copy` | 현재 공개 내용을 새 ID의 초안으로 복사. 성공 201. 이미 초안이 있으면 409 |
| POST 기준 경로 + `/from-extraction` | pdfSha256, expectedDraftId, expectedRevision. PDF 보정의 최신 추출 결과(AI 신청자격)와 지금 공급행으로 REVIEWING 초안 저장. 초안이 있으면 그 ID·revision일 때만 덮어씀. 응답은 ruleSet과 검토 안내 notices |
| POST 기준 경로 + `/validation` | draftId, expectedRevision의 초안 공개 검증. 성공 204 |
| POST 기준 경로 + `/publish` | draftId, expectedRevision의 초안을 현재 공개본으로 교체 |
| GET `/api/admin/basic-comparison/income-standards` | 실제 소득표 ID·종류·연도·금액·출처 조회 |

저장·복사·공개 응답은 `id`, `announcementId`, `status`, `revision`, `version`, `schemaVersion`,
`updatedAt`, `publishedAt`, `content`를 제공한다. 날짜는 ISO 날짜, 일정은 offset이 포함된 날짜·시각,
금액은 원 단위다. API DTO와 진입점은
[EligibilityRuleSetController](../../../backend/src/main/java/com/toadzip/backend/eligibility/controller/EligibilityRuleSetController.java)에 연결되어 있다.

## AI 추출 초안

전체 흐름과 설계 원칙은 [공고 PDF 신청자격 추출](extraction.md)에 있다. PDF 보정 화면은 보정 적용이 끝나면 초안이 없을 때 이 API를 바로 부른다. 적용 전에는 `GET /from-extraction/preview?pdfSha256=`이 같은 변환 결과(`EligibilityContent`)를 저장 없이 돌려준다. 공급행이 아직 없어 '미리보기' 공급단위 하나에 모든 경로를 잇는다. 추출이 없거나 PDF가 다르면 409다. `/from-extraction`은 PDF 보정 화면이 본 PDF(`pdfSha256`)가 최신 성공 추출과 같을 때만 만든다. 추출한 PDF가 공고 첨부에 있어야 원문 주소를 쓸 수 있고, 기준일은 모집공고일이다. 추출 결과 형식은 [워커 계약](../../../ai-worker/contract/README.md)의 `data.eligibility`다.

| 추출 결과 | 초안 |
|---|---|
| 공급행(관리자가 뺀 행·대상, 대상을 모두 뺀 행 제외) | supplyUnits. 모집수(공통 모집수 묶음이면 묶음 수)가 0보다 크면 RECRUITING, 모르면 UNKNOWN, 0이면 선택지를 만들지 않음 |
| `targets[]`와 `supplyTargets` | paths. 워커가 맞춘 공급대상 이름과 공백만 무시하고 같은 대상만 잇는다. 공급표가 대상을 나누지 않은 공급행(대상이 없거나 `전체`뿐)에는 모든 경로를 잇고 notices로 알린다. 워커가 대상을 맞추지 못한 경로가 나눈 공급행에 이어지지 못하면 그 이름도 notices로 알린다. 요건·자격을 하나도 뽑지 못한 경로에는 '신청 대상 …에 해당하는지' 직접 확인을 두어 공통 조건만으로 지원 가능이 되지 않게 한다. 잇지 못한 경로와 경로가 없는 공급대상은 공개 내용이 아니라 응답 notices로 관리자에게 알림 |
| 묶음의 `qualifications` | 그중 하나만 갖추면 되는 자격이라 자격마다 경로를 나눔 |
| 요건 | commonChecks·paths[].checks. 무주택세대구성원은 FAMILY_OWNS_HOUSE, 세대 소득은 HOUSEHOLD_MONTHLY_INCOME과 REGISTERED_FAMILY_COUNT. 출산자녀·맞벌이 가산이 있는 요건은 '기본 상한 이하 또는 가산을 받을 수 있는 사람(자녀·임신이 있는 한부모·부부, 맞벌이 부부)'으로 판정 |
| 워커가 판정 보류로 표시한 요건(`UNRESOLVED`) | REQUIRED_QUALIFICATION 안내 '공고문에서 직접 확인할 신청자격'(id `direct-…`)의 항목. 경로를 나누지 못한 공고의 경로별 요건, 모델이 빠뜨린 요건 자리이며 그 범위의 선택지를 지원 가능으로 확정하지 않음 |
| 판정에 넣지 않는 요건 | 조건에 연결하지 않은 REQUIRED_CHECK 안내 '공고문에서 확인할 자격'(id `list-…`)의 항목. 기타 요건(청약통장, 세대원 등재, 추천 등), 근거 없는 요건, 조건으로 바꾸지 못한 요건(워커가 비운 숫자, 없는 소득 기준표, 읽지 못하거나 시·도가 모호한 지역, 본인 소득·본인과 부모 합산 소득), 가산 안내(장애인 등 가산은 기본 기준으로 판정)가 해당하며 판정을 막지 않고 선택지의 `reminderGuidanceIds`로 사용자에게 보임 |
| `exclusions` | APPLICATION_EXCLUSION 안내 |

물량·임대조건·일정은 PDF 근거가 없는 보정 값이라 비워 둔다. 작성 규칙을 통과하지 못한 초안은 저장하지 않고 이유를 담은 409로 알린다. 초안은 검토 전이므로 관리자가 확인해 REVIEWED로 저장한 뒤 공개한다.

신청자격 탭은 조건마다 어떻게 확인했는지 보인다. 근거 문구를 원문 쪽에서 찾았는지와, 같은 PDF 추출의 워커 경고(요건 문구로 시작)
중 그 조건 문구와 요건 종류가 같은 것을 `확인 필요`로 곁에 둔다. 자산과 자동차가액이 원문 문구 하나를 함께 써도 경고는 제 종류의
조건에만 붙는다. 조건으로 바꾸지 못해 목록에 둔 요건과 '직접 확인으로 바꾸기'로 옮긴 항목은 `자산: <요건 문구>`처럼 종류를
앞에 붙여 제 경고만 받는다.
가산 안내 목록 문구는 늘 같은 요건의 조건과 함께 만들어지므로 경고를 붙이지 않고, 어느 기준인지(소득·자산·자동차가액)만 밝힌다. 경고가 없으면 숫자를 문구·쪽 글과 대조해 맞지 않는 숫자가 없었다고 쓰되,
어느 조건에도 붙이지 못한 숫자 경고가 있으면(이전 추출로 만든 초안 등) 그렇게 말하지 않고 위 경고 목록을 함께 보라고 한다.
결과 화면처럼 추출 경고가 없는 곳에서는 숫자 대조를 말하지 않는다.

## 검증과 공개 경계

검증기는 ID·교차 참조·근거 쪽수·안내 범위·금액·일정·입력 코드·소득표·지역을 확인한다.
모르는 속성, 실수의 정수 변환, 문자열 등의 묵시적 타입 변환은 거부한다. 내용은 4 MiB 이하이며
조건 트리의 깊이·항목 수도 제한한다. 상세 제한은
[내용 검증기](../../../backend/src/main/java/com/toadzip/backend/eligibility/domain/EligibilityContentValidator.java)와
[직렬화 경계](../../../backend/src/main/java/com/toadzip/backend/eligibility/repository/EligibilityJsonCodec.java)가 원본이다.

구조가 유효한 REVIEWING 초안은 저장할 수 있지만 공개는 REVIEWED만 허용한다. 자동 검증이 원문
해석을 대신하지 않으므로 실제 경로 누락, 자격·선정·임대료 조건의 혼동과 구조화 범위를 작성자가 검토한다.

공개는 정정 체인을 잠근 뒤 최신 게시물·공개 상태·관리자 삭제·취소 여부와 초안 ID/revision을 확인한다.
`취소공고`와 `CANCELLATION` 모두 차단한다. 검증과 교체는 한 트랜잭션이며 실패하면 기존 공개본과
관계형 행을 보존한다. 동시 공개는 한 요청만 성공한다. 공개 변경과 재판정 요청의 원자성은
[저장 설계](storage.md#갱신과-복구)를 따른다.

잘못된 내용은 400 `BASIC_COMPARISON_INVALID`, 기대 식별값·상태 충돌은 409
`BASIC_COMPARISON_CONFLICT`, 없는 공고 등은 404 `BASIC_COMPARISON_NOT_FOUND`로 구분한다.
원문 일부로 작성한 예제는 [공고 사례](decisions.md#공고-사례와-재현-fixture), 공개 실패·정정·동시성은
[공개 테스트](../../../backend/src/test/java/com/toadzip/backend/eligibility/controller/EligibilityRuleSetIntegrationTest.java)를 참조한다.

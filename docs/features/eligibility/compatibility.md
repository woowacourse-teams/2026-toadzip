# 이전 기본 조건 비교 API의 호환 범위

이전 기본 조건 비교는 사용자 정보로 비교할 수 있는 일부 조건과 직접 확인 안내를 제공한다.
현재 신청자격 판정과 같은 결과가 아니다. 현재 입력과 관리자 작성은 각각 [공통 입력](profile.md),
[공고 구조화](authoring.md)가 원본이며 이 문서는 기존 소비자의 API·데이터 호환 범위만 설명한다.

## 유지하는 API

USER 세션 인증, 사용자 ID의 세션 취득, `{"data": ...}` 응답과 `Cache-Control: no-store`를 적용한다.

| 요청 | 범위 |
|---|---|
| GET `/api/v2/announcements/{id}/basic-comparison` | 정정 체인의 최신 공고에서 기존 기본 비교·안내·근거·표시 자료 계산 |
| GET `/api/v2/eligibility/summary` | 접수 중 최신 공고의 기존 기본 비교·입력 부족·필수 자격 안내 집계 |

두 API는 요청 때 동기 계산하며 결과를 최신 신청자격 결과 테이블에 저장하지 않는다.
공통 프로필·카탈로그 API는 이전 비교 전용 API가 아니므로 [입력 문서](profile.md)에서 확인한다.

## 구조와 저장 버전의 호환

| 분류 | 작성·공개 검증 | 최신 신청자격 판정 |
|---|---|---|
| APPLICATION_ROUTES | 경로 하나 이상, 모두 APPLICATION. 각 경로에 공통/경로 조건 또는 필수 자격 연결 필요 | 개인 공고의 실제 선택지 평가 |
| REQUIRED_QUALIFICATION | 모든 경로 COMMON_ONLY, 공고 전체 REQUIRED_QUALIFICATION 안내 필요 | 이전 부분 비교이므로 NOT_EVALUATED |
| GENERAL_ROUTE_AVAILABLE | GENERAL 경로 하나 이상 필요 | 이전 부분 비교이므로 NOT_EVALUATED |
| ORGANIZATION 신청 주체 | 개인 경로·선택지 없음 | 개인 신청 판정 대상 밖 |

저장된 내용 schema 2·3을 읽으며 새 작성은 3이다. GENERAL/COMMON_ONLY 조합은 **읽기뿐 아니라
현재 저장·검증·공개도 허용**된다. 버전만 바꾸어 APPLICATION으로 자동 변환하지 않는다.
현재 계약의 상세 작성 규칙은 [관리자 문서](authoring.md#현재-신청자격-판정용-작성)를 따른다.

이전 기본 비교 API도 APPLICATION_ROUTES 내용을 거부하지 않고 읽는다. 그러나 `paths[].checks`만
비교하며 **commonChecks와 qualificationIds를 합산하지 않는다**. coverage에는 원래 path.kind를
그대로 반환하므로 GENERAL, COMMON_ONLY 외에 APPLICATION도 올 수 있다. RECRUITING만 비교하고
UPCOMING은 NOT_EVALUATED다. 따라서 MATCHED나 이전 요약을 최종 지원 가능 여부·건수로 사용하지 않는다.

## 상세 응답

상세 응답은 요청한 ID와 실제 최신 ID, 공개본 ID/version, 프로필 version, `availability`, `result`를 반환한다.
나이와 혼인기간의 기준일은 `result.referenceDate`다. 접속 날짜로 공고의 자격 기준일을 바꾸지 않는다.

| availability | 의미 |
|---|---|
| AVAILABLE | 현재 공개 내용을 비교할 수 있음 |
| NOT_STRUCTURED | 최신 공고에 현재 공개 내용이 없음. 초안·구 공개본으로 대체하지 않음 |
| NOT_PUBLISHED | 비공개 또는 관리자 삭제 공고 |
| CANCELLED | 취소된 최신 공고 |
| OUT_OF_SCOPE | 기업 신청·계약 유형. result에 제외 이유와 원문 정보 제공 |

NOT_STRUCTURED/NOT_PUBLISHED/CANCELLED에는 result가 없다. 존재하지 않는 공고는 404다.

`result.options`의 `coverage`는 위 호환 표의 경로 종류를 그대로 반환한다. 각 선택지에 단위·경로 이름, 모집 상태,
비교 상태와 문구, 항목별 `checks`, `missingInputs`, 해당 안내 ID를 제공한다.

| 비교 상태 | 해석 |
|---|---|
| MATCHED | 이 경로에서 자동 비교한 기본 조건 충족 |
| NOT_MATCHED | 이 경로에서 비교한 기본 조건 중 미충족 있음 |
| INPUT_NEEDED | 현재 받는 입력 중 비교에 필요한 값이 부족함 |
| PARTIALLY_COMPARED | 설정한 기본 조건의 적용을 확정하지 못해 직접 확인이 남음 |
| NOT_EVALUATED | 비모집·모집 여부 미확인 또는 자동 비교 항목 없음 |

기본 조건식의 판독은 [조건별 근거](decisions.md#조건별-근거)의 공통 평가기를 사용한다.
이 API가 그 결과를 합산하는 범위는 위에 명시한 경로 조건으로 제한된다.

각 `checks[].reading`은 상태, 필요한 입력, `comparedValues`를 반환한다. 소득 비교에는 입력 금액·한도·단위와
표 종류·연도·가족 수·비율이 있다. `checks[].evidenceIds`로 PDF 파일·쪽·표 위치를 찾는다.
`missingInputs`의 `optional`과 `skipped`를 사용해 생략한 가족정보를 필수 답변처럼 안내하지 않는다.

`result.directChecks`는 사용자 해당 여부를 계산하지 않는 안내다. 종류 순서대로 필수 안내부터 반환한다.

| 종류 | 안내 |
|---|---|
| REQUIRED_QUALIFICATION | 열거된 법정 자격, 이미 선정된 지구 대상자 등 신청 자체에 필수인 별도 자격 |
| REQUIRED_CHECK | 법정 세대 범위, 제한 이력, 가산 적용 등 신청 전 확인할 요건 |
| OTHER_ROUTE | 비교한 일반 경로 외에 원문에서 확인할 다른 신청 경로 |
| PRIORITY_OPPORTUNITY | 우선공급 대상·원문 확인 기회 |
| APPLICATION_EXCLUSION | 미수집 신청 제한 이력 등 특수 경우 |
| PROCEDURE | 중복신청·서류·입주 전 이행·접수 절차 |

안내 범위·공유 물량·임대조건·일정의 의미는 [구조화 계약](authoring.md#구조화-내용)을 따른다.
이전 SH 영구임대 fixture는 별도 자격을 안내 문구로 표현하지만, 현재 APPLICATION 경로와
qualifications로 구조화한 [D11 사례](decisions.md#공고-사례와-재현-fixture)는 별개의 계약이다.

응답의 원문 파일·근거·신청 단위·물량·임대조건·일정은 공고 표시 자료다.

`canApply`, `eligible`, `rank`, `score`, `selectionResults`는 이 기본 비교 응답에 없다.

## 접속 요약

[기존 공고 검색의 접수 정책](../../../backend/src/main/java/com/toadzip/backend/announcement/repository/ApplicationScheduleSql.java)을
재사용한다. 검토된 확정 일정이 있으면 원천 공고의 대략적인 날짜보다 우선한다. 조건부 접수만 있는 공고는
접수 중으로 세지 않는다. 최신 공개·미삭제 게시물만 대상으로 하고, 정정 전 게시물과 취소공고는 제외한다.
조회 묶음을 넘어도 keyset으로 모든 대상 공고를 한 번씩 처리한다.

| 필드 | 집계 |
|---|---|
| generalBasicMatchedCount | 모집 중인 GENERAL/MATCHED 선택지가 하나 이상인 개인 공고 |
| generalInputNeededCount | GENERAL/MATCHED가 없고 모집 중인 GENERAL/INPUT_NEEDED가 있는 공고 |
| requiredQualificationNoticeCount | 별도 필수 신청자격 안내가 있는 개인 공고 |
| comparedAnnouncementCount | 현재 공개 내용으로 비교한 개인 공고. 기본 비교 항목이 없는 경우도 포함 |
| notStructuredCount | 접수 중 최신 공고 가운데 현재 공개 내용이 없는 건수. 신청 주체도 미확인일 수 있음 |

같은 공고의 선택지가 여러 개여도 각 집계에서 한 번만 센다. COMMON_ONLY와 APPLICATION의 충족을 GENERAL 집계에 넣지 않는다.
필수 자격 안내 건수는 다른 건수와 겹칠 수 있으므로 합쳐서 전체 건수로 표시하지 않는다. 선택 입력이 부족하다는
집계도 입력을 강제하거나 입력 후 충족을 보장하는 의미가 아니다. 사용자 순위나 당일 접수 자격을 확정하지 않는다.

응답은 계산 시각·프로필 버전·비교 버전과 이 범위 설명을 함께 반환한다. 이 API의 계산 결과는 DB나 캐시에 적재하지 않는다. 최신 신청자격 결과의 보존 방식은 [저장 설계](storage.md)를 따른다.

## 이전 실행 경로와 데이터

이전 v1 회원·관리자 자격/선정 API는 제공하지 않으며 구 경로는 404다. 현재 `eligibility` 패키지가
같은 이름이라고 해서 구 평가·선정 엔진을 복원한 것은 아니다. 제거된 경로 목록과 구 데이터 비사용은
[전환 테스트](../../../backend/src/test/java/com/toadzip/backend/eligibility/controller/BasicComparisonCutoverIntegrationTest.java)에 남아 있다.

구 `announcement_rule_sets`, `eligibility_profiles`와 관련 테이블은 보존하지만 현재 API에서 읽지 않는다.
현재 공개 내용이 없으면 NOT_STRUCTURED, 현재 프로필이 없으면 UNKNOWN으로 시작한다.
원문과 입력 의미를 추정해 구 공개본·프로필을 자동 이전하지 않는다. 구 공개본을 쓰려면 원문을 다시
검토해 [현재 작성 계약](authoring.md)에 맞게 공개해야 한다.

`UserEligibilityInfo`/`user_eligibility_infos`도 선행 사용자 스키마의 별도 매핑이며 이 기능이 참조하지 않는다.
구 데이터를 보존하는 것과 현재 JSON을 관계형 행으로 투영하는 것은 다르다.
적용 조건·중단·검증·실패 대응은 [DB 전환 절차](../../operations/database/eligibility-upgrade.md)를 따른다.

## 현재 화면 연결과 계약 차이

현재 화면의 입력·저장 및 기본 비교 연결은
[basicComparisonRepository](../../../frontend/src/user/conditions/basicComparisonRepository.ts)가 담당한다.
프로필은 계약 3과 거주기간·계약 종료일을 사용하지만 판정은 여전히 `/basic-comparison`을 조회한다.
현재 화면 동작과 최신 신청자격 계약 사이에는 다음 차이가 있다. 최신 기능의 완료 기준으로 삼지 않는다.

| 현재 연결 | 신청자격 계약과의 차이 |
|---|---|
| COMMON_ONLY 외 coverage를 GENERAL로 변환하고 MATCHED를 집계 | APPLICATION의 일부 경로 조건 충족이 전체 자격 충족처럼 취급될 수 있음. 공통 조건·필수 자격이 빠진 이전 결과를 사용 |
| 현재 불러온 공고별 상세를 병렬 조회해 배너에서 “지원 가능 공고 N건” 표시 | 전체 모집·모집예정 공고의 최신 신청자격 집계가 아니며 `/eligibility/summary` 집계도 아님 |
| 공고 단위 matchedIds로 목록 제한 | 선택 지역 안의 실제 지원 가능 선택지 검사와 다름 |
| 저장 응답의 reevaluationState를 보존하지 않고 PUT 직후 “비교 결과에 반영했어요” 안내 | 저장 완료와 최신 판정 완료를 구분하지 못함 |

연결 코드는 [목록 결과 조회](../../../frontend/src/public-housing/announcements/useConditionListResults.ts),
[목록 배너](../../../frontend/src/public-housing/announcements/ConditionListBanner.tsx),
[입력 상태](../../../frontend/src/user/conditions/ConditionsProvider.tsx)에서 확인한다.
신청자격 화면으로 연결할 때 [판정·최신성·지역·발견 계약](decisions.md)을 적용해야 한다.
위 차이는 소스 계약의 차이이며 실제 브라우저·배포 동작을 확인한 기록은 아니다.

## 검증 진입점

기존 상세·요약 계약은
[기본 비교 API 테스트](../../../backend/src/test/java/com/toadzip/backend/eligibility/controller/BasicComparisonQueryIntegrationTest.java),
기존 예제 분류는 [BasicComparisonFixtures](../../../backend/src/test/java/com/toadzip/backend/eligibility/BasicComparisonFixtures.java)를 따른다.
현재 신청자격 fixture와 구분하며 실행 환경은 [검증 안내](overview.md#검증-범위)를 따른다.

# 신청자격 저장 구조 전환과 재판정 운영

공고 구조화 JSON을 관계형 행과 함께 저장하는 writer와 최신 결과·재판정 작업 테이블을 도입할 때의
절차다. 적용 대상은 primary 애플리케이션 DB다. 테이블·불변식은
[저장 설계](../../features/eligibility/storage.md), API 계약은 [기능 문서](../../features/eligibility/README.md)가 원본이다.
이 문서나 CI 통과는 특정 환경의 적용 완료를 뜻하지 않는다.

## 적용 조건

| 대상 상태 | 적용할 절차 |
|---|---|
| 새 빈 DB | 완성 릴리스로 전체 마이그레이션을 순서대로 적용하고 아래 사후 검증 수행 |
| Flyway 이력이 있는 기존 DB, 해당 마이그레이션 미적용 | 백업·복제본 검증 후 구 writer 중단을 포함한 아래 전환 수행 |
| V20261106_01~05 적용 완료 | 재백필하지 않는다. writer 호환과 투영·재판정 상태를 확인하고 후속 릴리스의 적용 조건을 검토 |
| 테이블은 있으나 Flyway 이력이 없는 DB | [Flyway 최초 도입](flyway.md)을 먼저 별도로 수행. 이 전환에서 임의 baseline으로 우회하지 않음 |

다음은 마이그레이션의 구분이다. 적용된 파일·checksum을 수정하지 않는다.

| 마이그레이션 | 변환·보존 범위 |
|---|---|
| [V20261106_01](../../../backend/src/main/resources/db/migration/V20261106_01__basic_comparison_current_content.sql) | 현재 작성 내용·프로필 테이블 생성. 구 자격 테이블을 자동 변환·삭제하지 않음 |
| [V20261106_02](../../../backend/src/main/resources/db/migration/V20261106_02__basic_comparison_median_income.sql) | 2026년 중위소득 기준 추가. 이미 있는 종류·연도 값은 덮어쓰지 않음 |
| [V20261106_03](../../../backend/src/main/resources/db/migration/V20261106_03__comparison_application_structure.sql) | 내용 계약 2/3 허용, 기존 basic_comparison_rule_sets JSON을 관계형 5개 테이블로 한 번 투영. 없는 지역·자격은 추론하지 않음 |
| [V20261106_04](../../../backend/src/main/resources/db/migration/V20261106_04__comparison_residence_profile.sql) | 입력 계약 2/3 허용. 기존 프로필 JSON·거주 정보를 UPDATE하거나 백필하지 않음 |
| [V20261106_05](../../../backend/src/main/resources/db/migration/V20261106_05__eligibility_latest_results_and_rechecks.sql) | 최신 결과·발견·작업·변경 트리거 추가. 모든 기존 회원과 최초 공고에 작업 생성. 참조된 소득표 불변성 보호 |

프로필 없는 회원의 초기 요청 버전은 0이다. 과거 판정 결과를 옮기는 작업은 없으며 worker가 계산한다.
구 테이블·API와 현재 저장 계약의 경계는 [호환 문서](../../features/eligibility/compatibility.md#이전-실행-경로와-데이터)를 따른다.

## 적용 전 준비

1. 승인된 운영자가 대상 환경·DB 식별자·앱 SHA·Flyway 이력과 실행 중인 모든 writer를 확인한다.
   primary DB와 shared DB를 구분한다. application/privacy Flyway는 같은 primary DB·public 스키마에서 별도 이력을 사용한다. 비밀값·프로필·전체 공고 JSON을 기록에 복사하지 않는다.
2. 전체 DB 백업과 격리 복원을 확인한다. 동일 릴리스의 전체 미적용 마이그레이션과 앱 기동을 복제본에서 검증한다.
   _03의 PK/FK/NOT NULL에 걸릴 기존 JSON 중복·참조 누락은 실제 데이터 복제본으로 확인해야 한다.
3. 기존 계약 버전 분포와 현재 작성 내용·프로필 건수를 기록한다. 복제본에서 새 writer의 초안 저장·복사·
   공개, 입력 계약 2 읽기와 3 저장, 최신/이전 API 계약을 확인한다. 서비스 DB에 임의 시험 공고를 만들지 않는다.
4. [특별 배포](../deployment.md#db-전환계약-변경이-필요한-특별-배포)에 따라 자동 배포와 별도 자동화를 조정한다.
   실행 중인 배포가 없는지 확인하고, 실패 시 어느 버전의 앱·DB를 복원할지 정한다.

앱은 트래픽을 받기 전에도 [마이그레이션 시작 구성](../../../backend/src/main/java/com/toadzip/backend/privacy/configuration/PrivacyMigrationConfiguration.java)에
따라 사전 검사 → application Flyway → privacy Flyway를 실행한다. 새 컨테이너 기동을 단순 읽기 점검으로
취급하지 않는다. 개인정보 스키마도 전환 대상이면 [개인정보 배포](privacy-deployment.md)를 함께 적용한다.

## 중단·적용·재개 순서

1. 구 공고 구조화·프로필 writer가 쓰지 못하게 한다. 인스턴스, 관리자 쓰기 요청, 자동화 등 실제 경로와
   차단 방법을 운영 기록에 남긴다. 저장소에는 이들을 일괄 차단하는 전용 토글이 없다.
2. 기동 전에 worker 실행 여부를 정한다. 백필 검증과 계산 시작을 분리하려면 새 앱에
   `comparison.recheck.enabled=false`를 설정한다. 이 값은 스케줄러만 끈다. Flyway·API 쓰기·
   변경 트리거·요청 누적은 계속되므로 writer 차단 수단이 아니다. 기본값은 true다.
3. 구 writer가 멈춘 상태에서 준비한 설정으로 새 릴리스를 기동해 마이그레이션을 적용한다.
   구 writer는 JSON만 갱신할 수 있어 새 관계형 투영을 자동으로 유지하지 못한다.
   일반 Blue/Green의 구·신 writer 동시 실행으로 대체하지 않는다.
4. Flyway 성공 이력, PK/FK/CHECK·트리거 정의와 아래 데이터 검사를 확인한다.
   오류가 있으면 쓰기 재개를 보류하고 실패 대응으로 넘어간다.
5. 새 writer만 재개한다. false로 시작했다면 true 설정으로 재기동해 예약 실행을 활성화한다.
   현재 입력·공고 기준의 결과가 생기는지, 커서·처리 상태가
   진행하는지 관찰한다. 저장 완료와 모든 재판정 완료를 같은 완료 조건으로 잡지 않는다.
6. 대상 환경에서 API 인증·호환·최신성, 서비스 오류와 적체를 확인하고 자동 배포를 재개한다.
   환경·SHA·적용 버전·관측 시각·검증 결과·남은 적체를 운영 이슈/PR에 남긴다.

## 사후 검증

[읽기 전용 점검 SQL](../../queries/eligibility-status.sql)은 _01~05 적용 후 실행한다.
psql의 연결 대상은 승인된 환경에서 별도로 선택하며, 원문·프로필 값 없이 집계만 출력한다.
대규모 투영 비교는 전체 작성 내용을 읽으므로 먼저 복제본에서 비용을 확인한다.

```sh
psql -X --set ON_ERROR_STOP=1 --file docs/queries/eligibility-status.sql
```

명령은 저장소 루트 기준이다. DB 접속 비밀값을 명령이나 기록에 직접 넣지 않는다.

- 프로필 현재 버전과 요청 버전 불일치, 회원·최초 공고의 작업 누락: 기대값 0.
- JSON과 단위·경로·자격·자격 연결·선택지 투영의 양방향 차이: 기대값 0.
  행 개수만 같은 경우까지 일치로 간주하지 않는다.
- PK/FK/CHECK와 트리거: 위 DDL과 대조한다. 최신 결과의 공고 FK는 공고 테이블 직접 참조가 아니라
  공고 작업 기준 행을 참조한다. 존재만 확인하지 말고 정의·활성 상태도 확인한다.
- 기존 프로필 계약 2를 읽고 새 항목 UNKNOWN으로 정규화하는지 확인한다. 읽기만으로 프로필을 재작성하지 않는다.
- 결과의 profile_version이 현재보다 낮은 것은 재판정 중 정상일 수 있다. API가 이 결과를 CURRENT로
  반환하지 않는지 확인한다. `/eligibility/processing`의 DONE만으로 모든 공고 결과의 최신성을 선언하지 않는다.

## 재판정 처리와 관측

설정 원본은 [스케줄러](../../../backend/src/main/java/com/toadzip/backend/eligibility/service/EligibilityRecheckScheduler.java),
[worker](../../../backend/src/main/java/com/toadzip/backend/eligibility/service/EligibilityRecheckWorker.java),
[작업 저장소](../../../backend/src/main/java/com/toadzip/backend/eligibility/repository/EligibilityWorkRepository.java)에 있다.

| 설정 | 기본값·제약 | 운영 의미 |
|---|---|---|
| comparison.recheck.enabled | true | false면 예약 실행만 중단, 영속 요청은 계속 누적 |
| comparison.recheck.poll-milliseconds | 1000 | 한 회차 완료 뒤 다음 회차까지의 지연 |
| comparison.recheck.batch-size | 32, 1~1000 제한 | 회차별 키 조회 수 |
| comparison.recheck.lease-seconds | 120, 최소 1초 | 작업 선점 유효 시간 |

회차는 공고 기준 대조·회원 작업·공고 작업을 작은 묶음으로 처리한다. 전용 단일 스레드 스케줄러를
사용하고 여러 인스턴스는 SKIP LOCKED로 선점한다. 실패 시 재시도 지연 식은
`least(300, 2^least(attempts, 8))`초이므로 현재 식에서 최대 지연은 256초다. 실패해도 요청을 버리지 않는다.
재시작하면 대조 커서는 처음부터 순회하며 DB의 실제 기준·작업 커서는 유지된다.

각 작업의 state별 건수, 만료 RUNNING, next_attempt_at, attempts·last_error_code, updated_at과 커서의
시간별 변화를 관찰한다. 단일 스냅샷의 대기 건수만으로 정체라고 판단하지 않는다.
프로필 저장 후 재입력을 요구하지 않고 미시작·실패·lease 만료 작업을 다시 처리한다.
취소·비공개·마감·은퇴 공고는 기존 결과가 있는 회원을 정리한다. 처리 대상 접수 상태와 최신성은
[판정 API](../../features/eligibility/decisions.md#단건-조회)를 따른다.

전용 대시보드·SLO나 100만 명 처리량 검증이 완료됐다는 뜻은 아니다. 활성 공고 수·결과 크기·
변경 빈도·동시 수정과 허용 지연을 정해 별도 측정한다. 결과·요청의 장기 정리 기간은
[미결 사항](../../features/eligibility/overview.md#아직-결정하지-않은-사항)이다.

## 실패와 복구

| 관찰한 문제 | 확인·대응 |
|---|---|
| 기동·마이그레이션 실패 | 추가 배포와 쓰기 재개를 멈추고 적용된 버전·현재 스키마·실패 원인을 확인. 뒤 파일 실패가 앞의 성공까지 롤백했다고 가정하지 않음 |
| JSON과 투영 불일치 | 구 writer 실행·직접 갱신 여부와 영향 작성 단위를 확인. 앱 재시작은 적용된 _03을 다시 실행하지 않음. 검증된 새 writer 재저장 또는 별도 보정안을 복제본에서 검증한 뒤 적용 |
| 재판정 미시작·정체 | enabled·앱 실행·DB 연결·작업 상태·오류·lease를 확인. 만료 claim은 재선점 가능하며 재활성화 후 커서 진전을 확인 |
| 반복 오류 | 오류 코드와 개인정보 없는 로그의 errorType으로 원인 확인 후 수정. 작업 삭제·수동 DONE 변경·커서 초기화로 완료처럼 만들지 않음 |
| 앱 이전 버전 복귀 필요 | 현재 schema3·입력·투영을 해당 버전이 읽고 보존할 수 있는지 먼저 확인. 호환이 검증되지 않은 구 writer는 재개하지 않음 |

앱 롤백은 이미 성공한 DB 변경을 되돌리지 않는다. 수정 릴리스 또는 검증한 백업 복원 계획 중
대상 상태에 맞는 방법을 선택한다. 백업 복원은 그 이후 쓰기의 손실 범위까지 별도로 확인한다.
Flyway repair/clean, 트리거 해제, 적용 SQL 수정은 일반 복구 수단이 아니다.
참조 중 소득표는 같은 ID로 수정하지 않으며 새 기준표와 공개본으로 정정한다.

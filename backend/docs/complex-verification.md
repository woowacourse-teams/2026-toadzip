# 관리자 단지 검증

단지 상세의 검증 화면은 수집값 비교와 관리자의 직접 확인을 구분한다.
원천값 일치는 실제 정보 확인 완료를 의미하지 않는다.

## 근거 조회

- `GET /api/admin/housing-complexes/{id}/verification`은 현재값, 연결 마이홈 원천,
  최근 검토 및 최근 20건의 검토 이력을 반환한다.
- 원천은 `myhome_complex_links`의 식별자와 공급유형으로 연결한다. 연결이 없으면
  단지 자체의 정확한 마이홈 식별자만 사용한다. 이름·주소의 유사 검색으로 추정하지 않는다.
- 통합된 단지는 각 원천값을 보존하며 세대수를 합산하거나 대표값으로 바꾸지 않는다.
- 원천에 좌표가 없으므로 저장 좌표와 주소를 지도에서 직접 확인한다.

## 검토 기록

- `POST /api/admin/housing-complexes/{id}/verification/reviews`에 `version`,
  `reviewId`(첫 검토는 0), `snapshotToken`, `fields`, `outcome`,
  `evidenceUrl`(선택), `evidenceNote`(필수)를 전달한다.
- 확인 범위는 NAME, ADDRESS(주소·PNU·지역 식별정보), LOCATION, AGENCY,
  RENTAL_TYPE, HOUSEHOLD_COUNT다. 하나 이상 선택하며 중복 선택을 거부한다.
- 결과는 VERIFIED 또는 ON_HOLD이며 선택한 항목의 당시 값과 확인자·시각을
  수정 불가능한 새 검토 기록으로 보존한다. 단지의 관리자 수정 보호 상태는 변경하지 않는다.
- 관리자 권한과 CSRF를 요구한다. 파이프라인 소유권과 단지 행 잠금으로 쓰기를 보호하며,
  조회 후 현재값·원천·최근 검토가 달라졌으면 409로 다시 조회하도록 안내한다.
- 휴지통 단지는 이전 검토를 조회할 수 있지만 새 검토는 저장할 수 없다.

## 상태와 목록 필터

- 검토 없음은 UNREVIEWED, 선택 항목이 당시 값과 같으면 최근 검토 결과,
  선택 항목이 달라졌으면 STALE다. 확인하지 않은 항목의 변경은 영향을 주지 않는다.
- 상태는 조회 시 현재값과 JSONB 검토 스냅샷을 대조한다. 관리자 수정·자동 정제·위치 보강·
  단지 통합 등 어느 경로에서 변경되어도 별도 초기화 코드 없이 적용된다.
- 검토 상태는 등록값에 대한 확인 범위다. 새 수집 원천과의 차이는 비교 화면과
  기존 `sourceReviewRequired`로 별도 표시한다.
- 목록의 선택적 `verification=UNREVIEWED|VERIFIED|ON_HOLD|STALE` 필터와
  `complex.verificationStatus`, `complex.reviewedFieldCount`로 같은 상태를 제공한다.
- Flyway `V20261009_01`은 검토 이력 테이블과 단지별 최신 기록 인덱스를 추가한다.
  기존 단지 데이터는 변경하지 않는다.

검증: `ComplexVerificationIntegrationTest`와 `AdminManagementIntegrationTest`.

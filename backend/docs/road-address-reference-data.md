# 단지 도로명주소 좌표 적재

[도로명주소](https://www.juso.go.kr/)의 `위치정보요약DB_전체분.zip`에서 단지 주소와 일치하는 출입구 좌표만 저장한다.
원본 좌표는 GRS80 UTM-K다. 전국 데이터를 그대로 복제하거나 일변동 연계 API를 사용하지 않는다.

## 실행

관리자 세션·CSRF로 다음 순서대로 실행한다.

1. `POST /api/admin/ingest/myhome/complexes`로 단지 원천을 수집한다.
2. `POST /api/admin/ingest/juso/location-summaries`에 월 전체분 ZIP을 multipart `file`로 업로드한다.
3. `targetRoadAddressCount`, `matchedRoadAddressCount`, `unmatchedRoadAddressCount`를 확인한다.
4. `POST /api/admin/ingest/myhome/complex-mappings`로 단지를 정제한다.

## 검증과 교체

ZIP의 16개 지역 TXT가 모두 있고 비어 있지 않으며 예상 시도코드에 맞아야 한다.
ZIP·TXT는 CP949를 지원하고 각 행은 18개 컬럼이어야 한다.

전체 파일을 검증하고 주소 일치 행을 선별한 뒤 `road_address_locations`를 교체한다.
기존 삭제·PostgreSQL `COPY` 적재는 한 트랜잭션이다. 검증·DB 오류나 일치 행 0건이면 기존 데이터를 유지한다.
단지 정제와 좌표 적재는 같은 실행 잠금을 사용한다. 일반 단지 정제는 저장된 좌표를 매번 조회한다.

비공개·제한 건물은 원본에 좌표가 없을 수 있다. 해당 단지는 좌표 없음으로 남긴다.
새 전체분이 필요할 때만 같은 API로 교체하며 정기 실행·주소 API 인증키는 사용하지 않는다.

스키마는 [Flyway](flyway-adoption.md)를 따른다. 앱 롤백 시에도 참조 좌표를 보존한다. 테이블 삭제는 별도 승인을 받는다.

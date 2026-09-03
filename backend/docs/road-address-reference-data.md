# 단지 도로명주소 좌표 적재

## 선택한 데이터

도로명주소 사이트에서 내려받은 `위치정보요약DB_전체분.zip`을 사용한다. 이 파일에는 전국 출입구의
GRS80 UTM-K 좌표가 들어 있다. 최신 좌표를 계속 반영하는 기능은 필요하지 않으므로 일변동 연계 API와
승인키는 사용하지 않는다.

전국 약 642만 행을 서비스 DB에 그대로 복제하지 않는다. 마이홈 단지 원천 데이터의 고유 도로명주소를
메모리에 준비하고, 전체분 ZIP을 한 번 스트리밍하면서 주소가 일치하는 출입구 행만
`road_address_locations`에 저장한다. 따라서 상시 저장량은 전체 주소 규모가 아니라 단지 주소 규모에
비례한다.

## 실행 순서

1. `POST /api/admin/ingest/myhome/complexes`로 마이홈 단지 원천 데이터를 먼저 수집한다.
2. `POST /api/admin/ingest/juso/location-summaries`에 월 전체분 ZIP을 multipart의 `file`로 전송한다.
3. 응답의 `targetRoadAddressCount`, `matchedRoadAddressCount`, `unmatchedRoadAddressCount`를 확인한다.
4. `POST /api/admin/ingest/myhome/complex-mappings`로 단지 매핑을 실행한다.

```bash
curl -X POST \
  -F 'file=@202607_위치정보요약DB_전체분.zip;type=application/zip' \
  http://localhost:8080/api/admin/ingest/juso/location-summaries
```

## 적재 안전성

- ZIP 안에 명세의 16개 지역 TXT가 모두 있어야 전국 전체분으로 인정한다.
- ZIP과 TXT는 실제 배포 형식에 맞춰 CP949를 지원하고, 행은 18개 컬럼이어야 한다.
- 전체 ZIP을 먼저 검증하고 일치 행만 메모리에 선별하므로 파일 스캔 중에는 DB 교체 잠금을 잡지 않는다.
- 기존 선별 데이터 삭제와 새 데이터의 PostgreSQL `COPY` 적재만 짧은 한 트랜잭션에서 실행한다.
- 파일 오류, 지역 파일 누락 또는 DB 오류가 발생하면 전체 트랜잭션을 롤백해 기존 데이터를 유지한다.
- 단지 매핑과 선별 적재는 같은 실행 잠금을 사용하므로 동시에 실행되지 않는다.
- 비공개·제한 건물은 원본에서 좌표가 비어 있을 수 있으며 해당 단지는 좌표 없음으로 기록된다.

전체분을 다시 받을 필요가 생긴 경우에만 같은 API로 교체한다. 정기 스케줄과 주소 API 인증키 설정은
필요하지 않다.

## 스키마 배포

운영은 `ddl-auto=validate`이므로 애플리케이션 배포 전에 다음 SQL을 실행한다.

```bash
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260903_03__create_road_address_locations.sql
```

SQL은 신규 좌표 테이블과 주소 조회 인덱스만 추가하므로 이전 애플리케이션과 함께 적용할 수 있다.
롤백 시에도 선별 좌표는 재사용 가능한 참조 데이터이므로 테이블을 보존한다. 테이블 삭제는 별도 승인 후
수행한다.

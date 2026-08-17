# 적재 실행

현재 적재 범위는 다음과 같다.

- 마이홈 단지 카탈로그 원천과 `housing_complex`·`unit_type` 투영
- 마이홈 현재 공고 원천과 `notice`·`notice_supply` 투영
- LH 공고 상세·주택형 공급 원천과 공고 부가 정보 투영
- LH 임대 카탈로그 원천과 `unit_type` 투영
- 공고 공급행과 카탈로그 단지·주택형 연결
- 저장된 원천만 다시 읽는 재투영

과거 공고 목록 원천(`15058530`)과 과거 공고 적재 endpoint는 현재 범위에서 제외한다.

## 로컬 실행

서비스 키를 설정한 뒤 백엔드를 실행한다.

```bash
export DATA_GO_KR_SERVICE_KEY='발급받은-서비스키'
cd backend
./gradlew bootRun
```

H2 테스트 데이터베이스는 Gradle 테스트에서 자동으로 사용한다. 팀 공용 실행은 PostgreSQL을 사용한다.

```bash
cp .env.example .env
# .env의 POSTGRES_PASSWORD와 DATA_GO_KR_SERVICE_KEY 입력
docker compose up --build
```

`compose.yaml`의 `SPRING_JPA_HIBERNATE_DDL_AUTO=update`는 현재 마이그레이션 도구가 없는 개발 환경에서만 사용한다.

## 실행 순서

외부 API를 호출하는 적재는 다음 순서로 실행한다. 각 endpoint는 동기식이며, 한 번에 한 명의 운영자만 실행한다.

```bash
# 마이홈 단지: 지역별 snapshot 적재 및 투영
curl -X POST 'http://localhost:8080/admin/ingest/complexes?provinceCode=11&districtCode=110'

# 마이홈 현재 공고: 7개 공급유형 조회, 원천 저장, 공고 투영
curl -X POST 'http://localhost:8080/admin/ingest/notices'

# LH 공고 상세·주택형 공급: 기존 LH 공고의 panId 대상
curl -X POST 'http://localhost:8080/admin/ingest/lh-notices?refresh=false'

# LH 임대 카탈로그
curl -X POST 'http://localhost:8080/admin/ingest/lease-infos'

# 공고 공급행과 카탈로그 연결만 다시 계산
curl -X POST 'http://localhost:8080/admin/ingest/links'
```

외부 호출 없이 저장된 typed 원천을 다시 투영하려면 다음 endpoint를 사용한다. 순서는 단지 → 공고 → LH 카탈로그 → LH 공고 상세·공급 → 연결이다.

```bash
curl -X POST 'http://localhost:8080/admin/ingest/rebuild-from-sources'
```

응답의 `created`, `updated`, `unchanged`, `failed`, `rejectedByReason`으로 단계별 결과를 확인한다. 원천 snapshot은 재투영의 입력으로 보존되며, 공고 연결은 카탈로그가 나중에 들어온 경우에도 `/links` 또는 재투영 endpoint로 다시 계산할 수 있다.

현재 endpoint에는 인증·인가, scheduler, queue, distributed lock이 없다. 외부에 공개하지 말고 운영 실행 시 동시 호출을 피한다.

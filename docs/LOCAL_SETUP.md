# 로컬 개발 환경

## 실행

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

## API 문서

로컬 프로필에서만 Swagger UI와 OpenAPI 명세를 제공한다.

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

`.env`에서 `BACKEND_PORT`를 변경했다면 URL의 `8080`을 같은 포트로 바꾼다.

## DB GUI 연결

```text
Host: 127.0.0.1
Port: ${POSTGRES_PORT:-5433}
Database: toadzip
Username: toadzip
Password: .env의 POSTGRES_PASSWORD
```

## 관리자 데이터 등록 수동 검증

로컬 관리자 계정이 없다면 `.env`에 다음 값을 설정한 뒤 백엔드를 최초 한 번
기동한다. 계정 생성 후에는 `ADMIN_BOOTSTRAP_ENABLED=false`로 되돌린다. 실제
비밀번호는 Git이 추적하는 파일에 기록하지 않는다.

```dotenv
ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_LOGIN_IDENTIFIER=로컬_관리자_식별자
ADMIN_PASSWORD=로컬_관리자_비밀번호
```

백엔드와 PostgreSQL을 기동하고, 별도 터미널에서 프론트엔드 개발 서버를 실행한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build --wait
cd frontend
npm ci
npm run dev
```

브라우저에서 `http://localhost:5173/admin`을 열고 다음 순서로 확인한다.

1. 로컬 관리자 계정으로 로그인한다.
2. 단지 기본 정보, 주소, 시설 정보를 입력하고 `단지 저장`을 누른다.
3. 저장 중에는 버튼이 비활성화되고, 완료 후 성공 메시지와 선택 단지가 표시되는지 확인한다.
4. 선택 단지가 방금 저장한 단지인지 확인한다.
5. 공고 기본 정보, 접수처, 단일 공급행을 입력하고 `공고 저장`을 누른다.
6. 저장 중에는 버튼이 비활성화되고, 완료 후 성공 메시지가 표시되는지 확인한다.
7. 브라우저 네트워크 패널에서 각 POST 직전에 `/api/admin/auth/csrf`를 호출하고,
   응답의 동적 헤더 이름과 토큰 및 세션 쿠키를 POST에 포함했는지 확인한다.

저장된 세 테이블과 외래 키는 다음 쿼리로 확인한다. PostgreSQL 사용자나 DB 이름을
변경했다면 명령의 기본값도 같은 값으로 바꾼다.

```shell
docker compose -f compose.yaml -f compose.local.yaml exec db \
  psql -U "${POSTGRES_USER:-toadzip}" -d "${POSTGRES_DB:-toadzip}" -c '
    SELECT
      hc.id AS housing_complex_id,
      a.id AS announcement_id,
      sr.id AS supply_row_id,
      sr.housing_complex_id AS supply_row_housing_complex_id,
      sr.announcement_id AS supply_row_announcement_id,
      sr.housing_type_id
    FROM supply_rows sr
    JOIN housing_complexes hc ON hc.id = sr.housing_complex_id
    JOIN announcements a ON a.id = sr.announcement_id
    ORDER BY sr.id DESC
    LIMIT 1;
  '
```

`supply_row_housing_complex_id`와 `housing_complex_id`,
`supply_row_announcement_id`와 `announcement_id`가 각각 같고,
`housing_type_id`가 `null`이어야 한다.

## 종료

```shell
docker compose -f compose.yaml -f compose.local.yaml down
```

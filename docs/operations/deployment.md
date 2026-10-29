# 개발·운영 배포

아래는 현재 저장소의 워크플로가 수행하는 동작이다. 서버의 비밀값·실제 실행 결과는 포함하지 않는다.
명령과 상대 경로는 각 서버의 저장소 루트를 기준으로 한다.

## 자동·수동 실행

| 환경 | 자동 실행 | 수동 실행 브랜치 | Runner 라벨 | 저장소 경로 변수 |
| --- | --- | --- | --- | --- |
| 개발 | `develop` 푸시 | `develop` | `toadzip-dev` | `DEV_DEPLOY_PATH` |
| 운영 | `main` 푸시 | `main` | `toadzip-prod` | `PROD_DEPLOY_PATH` |

[개발 배포](../../.github/workflows/deploy-dev.yml)와 [운영 배포](../../.github/workflows/deploy-prod.yml)는
[백엔드 CI](../../.github/workflows/backend-ci.yml)·[프론트 CI](../../.github/workflows/frontend-ci.yml)를 호출한다.
두 검사가 통과한 뒤 해당 서버의 Self-hosted Runner가 배포한다. PR 검사는 배포하지 않는다.
수동 실행도 위 브랜치 조건을 적용한다.

서버의 Runner는 Git 저장소와 Docker를 실행할 수 있어야 한다. GitHub의 Actions 저장소 변수에
실제 프로젝트 절대 경로를 등록한다. Runner 설치 폴더가 아니라 `.git`·Compose 파일이 있는 경로다.
경로가 틀리면 Git 명령부터 실패한다. 변수나 로그에 실제 인증정보를 넣지 않는다.

## CI 실행 범위

- PR은 `dorny/paths-filter`로 변경된 영역만 검증한다. 설명 문서만 바뀌면 무거운 검증을 생략한다.
  루트 설정, `.github/`, `scripts/`, `tests/`의 실행 파일과 공통 테스트 자료 `docs/fixtures/` 변경은 양쪽 검증을 실행한다.
  백엔드의 지도 대표 좌표 CSV도 프론트엔드 일치 검사에서 읽으므로 양쪽을 검증한다.
  [ci-paths.yml](../../.github/ci-paths.yml)의 제외 규칙은 `some-with-excludes` 방식으로 평가한다. 소스 디렉터리 안의 Markdown 리소스는 제외하지 않는다.
- [백엔드 CI](../../.github/workflows/backend-ci.yml)는 Flyway 검사와 백엔드 테스트 두 분할을 실행하고 `Backend Check`로 결과를 모은다.
  [프론트 CI](../../.github/workflows/frontend-ci.yml)는 린트·테스트·빌드와 알림 배포 설정을 `Frontend Check`로 검사한다.
  경로 감지가 실패하거나 필요한 테스트가 실패·취소되면 통과하지 않는다.
  백엔드는 두 분할이 모두 성공해야 통과하며, 변경이 없어서 분할을 생략한 경우만 성공으로 처리한다.
- `develop`·`main` 배포에서 재사용 워크플로를 호출하면 경로 필터 없이 전체를 검증한다.
  호출하는 워크플로에도 `contents: read`, `pull-requests: read` 권한이 필요하다.
- Gradle 캐시는 PR에서 읽기 전용이며, `develop`·`main`의 `application` 분할만 저장한다. 두 분할 모두 전체 소스를 컴파일한다.
  `Set up Gradle` 로그의 복원 결과와 `compileJava`·`compileTestJava`의 `FROM-CACHE`를 확인한다. `test --rerun`으로 테스트는 매번 실행한다.
- 백엔드 보고서는 `backend-test-reports-ingest`와 `backend-test-reports-application` 아티팩트에 각각 저장된다.
  로컬 분할 실행 방법은 [백엔드 테스트 전략](../development/backend/testing.md#실행-순서)을 따른다.

## Blue/Green 서버 준비

Runner에는 Git·Bash·`flock`·Python 3·Docker Compose가 필요하다. 서버별 `.env`는 기존대로 보존하며
개발은 `SPRING_PROFILES_ACTIVE=dev`, 운영은 `prod`를 사용한다. 해석된 Compose 환경값을 로그에 출력하지 않는다.

| 저장소 변수 | 용도 |
| --- | --- |
| `DEV_DEPLOY_PATH` / `PROD_DEPLOY_PATH` | 기존 서버 체크아웃의 절대 경로 |
| `DEV_BLUEGREEN_URL` / `PROD_BLUEGREEN_URL` | 실제 Edge/Caddy를 거치는 공개 HTTPS 주소. 미설정이면 배포 중단 |
| `DEV_BLUEGREEN_RUNTIME` / `PROD_BLUEGREEN_RUNTIME` | 선택 설정. 기본은 체크아웃의 `infra/runtime/bluegreen` |
| `DEV_BLUEGREEN_COMPOSE_OVERRIDES` / `PROD_BLUEGREEN_COMPOSE_OVERRIDES` | 선택 설정. 추가 서버 Compose 파일 경로를 한 줄에 하나씩 지정 |

`python3 -B scripts/prepare-bluegreen.py`로 `infra/runtime/bluegreen/`을 준비하고
설정 마운트·정적 파일 보관 경로·실제 진입점 전환을 먼저 완료한다.
배포 상태는 Git에서 제외하며, 설정 원본은 `infra/nginx/edge/default.conf`로 관리한다.
[Linux 배포 도구](../../scripts/README.md#linux-프론트백엔드-bluegreen-도구)의 초기 준비를 따른다.
워크플로는 최초 컨테이너·TLS·Caddy·모니터링·DB를 생성하거나 공개 포트를 변경하지 않는다.
기존 프론트 컨테이너가 진입점인 서버에서는 먼저 공개 진입점 전환을 완료한다.

기존 `.env`의 `COMPOSE_FILE`은 이 명령에서 자동 적용하지 않는다. 도구는 `compose.yaml`·`compose.bluegreen.yaml`과
위 변수로 명시한 추가 파일만 사용한다.
이전 경로를 `*_BLUEGREEN_RUNTIME`이나 서버 `EDGE_NGINX_*`에 지정했다면 새 경로로 함께 변경한다. ADC 등 런타임 설정을 legacy `backend`에만 덧붙이는 overlay는
두 색상에도 같은 필수 환경·마운트를 전달해야 한다. 누락·환경 프로필 불일치·후보 호스트 포트 공개는 사전 검사에서 거부한다.
서버 인증정보는 GitHub 저장소 변수에 넣지 않고 기존 서버 파일·자격 관리 방식을 유지한다.

## 서버에서 실행하는 작업

개발·운영 모두 다음 순서로 실행한다.

1. 공개 HTTPS 주소와 기존 런타임 경로를 확인하고, 수동 배포와 공유하는 `deploy.lock`을 잡는다.
2. 로컬 변경이 없는지 확인한다. `git fetch` 후 CI 실행의 `GITHUB_SHA`가 대상 브랜치의 이력에 속하는지 확인하고
   해당 커밋으로 detached checkout한다. 최신 브랜치 커밋으로 대체하지 않는다. 기존 서버 환경 파일·개인 자료는 삭제하지 않는다.
3. [`release-bluegreen.py`](../../scripts/release-bluegreen.py)가 체크아웃 SHA·깨끗한 작업 트리·환경 프로필·필수 런타임 설정을 확인한다.
   두 서비스의 현재 경로·응답과 Edge 정적 파일 제공까지 확인한 뒤 빌드한다.
4. `toadzip-backend:<SHA>`와 `toadzip-frontend:<SHA>`를 순차 빌드한다. 기존 Compose 빌드 인자와 Dockerfile을 사용하고
   이미지에 `org.opencontainers.image.revision`을 기록한다. 두 이미지의 빌드·라벨 검증이 모두 끝나야 배포한다.
5. 고정된 이미지 ID로 백엔드 → 프론트 순서로 후보 기동·전환·요청 종료 대기·이전 컨테이너 정지를 실행한다.
   Git 갱신부터 빌드·두 서비스 전환까지 같은 잠금을 유지한다.
6. 성공·실패 단계, 완료한 서비스, 빌드 이미지 ID와 런타임 설정의 연결 색상을 Actions 로그·Step Summary 및
   런타임 `releases/<SHA>-<임의 ID>.json`에 남긴다. 실제 수신 경로가 확인되지 못한 실패에서는 로그·설정 백업도 함께 점검한다.

워크플로가 내부에서 실행하는 명령은 다음과 같다. `--lock-fd 9`는 준비 단계부터 부모 셸이 보유한 잠금이며,
일반 수동 실행에서는 이 옵션을 생략하면 도구가 같은 잠금을 직접 획득한다.

```shell
python3 -B scripts/release-bluegreen.py \
  --revision "$CI_COMMIT" --environment dev \
  --runtime "$runtime" --url "$BLUEGREEN_URL" --lock-fd 9
```

- 환경별 Actions concurrency를 유지하고 진행 중인 배포를 취소하지 않는다. 배포 job 제한은 기존 30분이다.
- 서버에서 이미지를 빌드하며 GHCR은 사용하지 않는다. 빌드는 서비스별로 순차 수행한다.
- 기존 `.env`·인증서·볼륨을 유지한다. Git 작업 트리가 수정되었으면 강제 reset·clean 없이 중단한다.
- 프론트 빌드 실패는 백엔드 전환 전에 중단한다. 백엔드 배포 실패는 프론트 배포를 실행하지 않는다.
- 백엔드 전환 후 프론트 배포가 실패하면 **백엔드는 새 버전으로 남을 수 있다.** 서비스별 경로 복구와
  전체 릴리스의 원복을 구분한다. 보고된 실제 상태와 백업·DB 호환성을 확인한 뒤 필요한 서비스만 원복한다.
- 요청 경로 복구는 이미 성공한 Flyway 마이그레이션을 취소하지 않는다. 아래 특별 배포 절차는 별도로 적용한다.

CI에서 실행하는 빠른 배포 검사는 `tests/deployment/test_bluegreen.py`와 `test_release_bluegreen.py`다.
실제 Linux 빌드·전환을 시험하는 격리 검증은 [검증 안내](../../tests/README.md#linux-릴리스-통합-검증)를 따른다.
코드 변경·로컬 시험 성공은 실제 Actions 실행 또는 개발·운영 서버 적용 완료를 뜻하지 않는다.

## DB 전환·계약 변경이 필요한 특별 배포

현재 워크플로는 호스트의 개인정보·알림 스키마 점검 스크립트와 writer의 단계별 중지를 실행하지 않는다.
앱 내부의 시작 검사·Flyway·healthcheck와 별도 승인·백업·복제본 검증·사후 점검은 다른 절차다.

기존 DB의 최초 Flyway·개인정보 전환이나 구버전과 혼용할 수 없는 릴리스는 다음을 적용한다.

1. develop·main 병합이 자동 배포를 시작한다는 점을 먼저 확인한다. 승인된 정비 작업에서는
   해당 자동 배포를 중지하고 진행 중인 배포가 없는지 확인한다. 저장소 밖 자동화도 함께 확인한다.
2. 대상 DB·백업·격리 복제본과 완성 릴리스를 준비한다. 기존 DB 도입은
   [Flyway 절차](database/flyway.md), 개인정보 전환은 [개인정보 배포](database/privacy-deployment.md)를 따른다.
3. 해당 문서의 중지·검사·기동·사후 확인을 승인된 운영자가 수행한다.
   일반 Blue/Green 배포로 특별 배포를 대신하지 않는다.
4. 스키마·서비스·로그인 등 해당 기능 확인을 마친 뒤 자동 배포를 재개한다.

알림 스키마의 별도 읽기 전용 점검은 [알림 운영](notifications.md)을 따른다.
운영 DB에 대한 실행·복구 승인은 문서 수정이나 CI 성공으로 대신하지 않는다.

## 배포 실패 시

Actions의 실패 단계와 서버의 실제 브랜치·커밋·컨테이너 상태·로그를 확인한다.
Git 갱신 실패, 빌드 실패와 healthcheck 실패를 구분하며 비밀값이나 전체 환경변수를 출력하지 않는다.
컨테이너를 일괄 내리거나 볼륨을 삭제해 해결하지 않는다.

이미 적용된 마이그레이션은 파일·이력·checksum을 임의로 바꾸거나 repair하지 않는다.
앱만 이전 버전으로 되돌릴 수 있는지는 각 기능의 호환성·복구 절차와 대상 DB 상태로 판단한다.
이 문서의 존재는 실제 개발·운영 배포나 별도 스키마 점검의 성공 증거가 아니다.

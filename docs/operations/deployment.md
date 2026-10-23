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

## 서버에서 실행하는 작업

워크플로는 해당 브랜치로 전환한 뒤 `git pull --ff-only`로 가져오고 다음 명령을 실행한다.

```shell
docker compose up -d --build --wait --wait-timeout 180
```

운영은 pull 뒤 현재 커밋과 실행의 `GITHUB_SHA`가 같은지도 확인한다. 개발에는 이 검사가 없다.
추가 push가 겹친 개발 실행은 최초 CI 대상보다 최신 커밋을 가져올 수 있으므로,
실행의 커밋과 실제 서버 커밋을 구분한다.

- 환경별 배포 동시 실행은 concurrency로 제한하며 진행 중인 배포를 취소하지 않는다.
- 배포 job 제한은 30분이다. 이미지 빌드는 서버에서 수행하며 GHCR을 사용하지 않는다.
- `--wait`는 healthcheck가 있으면 healthy, 없으면 실행 상태를 기다린다.
  180초는 준비 대기 제한이며 이미지 빌드 시간과 별개다.
- 서버의 `.env`, HTTPS overlay·인증서와 기존 볼륨은 유지한다.
- healthcheck 실패는 자동 롤백이나 이미 성공한 DB 마이그레이션의 취소를 뜻하지 않는다.

처음 서버를 준비할 때의 환경값·기동은 [서버 설정](../setup/servers.md),
인증서 설정은 [HTTPS 운영](https.md)을 따른다.

## DB 전환·계약 변경이 필요한 특별 배포

현재 워크플로는 호스트의 개인정보·알림 스키마 점검 스크립트와 writer의 단계별 중지를 실행하지 않는다.
앱 내부의 시작 검사·Flyway·healthcheck와 별도 승인·백업·복제본 검증·사후 점검은 다른 절차다.

기존 DB의 최초 Flyway·개인정보 전환이나 구버전과 혼용할 수 없는 릴리스는 다음을 적용한다.

1. develop·main 병합이 자동 배포를 시작한다는 점을 먼저 확인한다. 승인된 정비 작업에서는
   해당 자동 배포를 중지하고 진행 중인 배포가 없는지 확인한다. 저장소 밖 자동화도 함께 확인한다.
2. 대상 DB·백업·격리 복제본과 완성 릴리스를 준비한다. 기존 DB 도입은
   [Flyway 절차](database/flyway.md), 개인정보 전환은 [개인정보 배포](database/privacy-deployment.md)를 따른다.
3. 해당 문서의 중지·검사·기동·사후 확인을 승인된 운영자가 수행한다.
   일반 Compose 일괄 실행으로 특별 배포를 대신하지 않는다.
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

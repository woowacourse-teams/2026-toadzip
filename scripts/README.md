# 공통 스크립트

배포 설정을 검사하고 DB 전환·복원 작업을 지원하는 스크립트를 관리합니다.

## 스크립트 안내

| 스크립트 | 용도·조건 |
| --- | --- |
| [`check-flyway-migrations.py`](check-flyway-migrations.py) | Git에 커밋된 Flyway SQL의 버전 중복, develop보다 낮거나 같은 신규 버전, 기존 SQL의 수정·삭제·이름 변경을 검사합니다. |
| [`test-https.py`](test-https.py) | 임시 인증서로 로컬 HTTPS·Nginx 갱신 hook을 검증합니다. `--config-only`는 Compose 설정과 hook 검사만 수행합니다. |
| [`deploy-bluegreen.ps1`](deploy-bluegreen.ps1) | Windows 로컬 검증용입니다. 화면이 Blue로 전달될 때 기존 로컬 이미지로 Green 프론트엔드를 재생성하고 준비 상태를 확인합니다. 화면 전환은 별도 명령입니다. |
| [`switch-frontend.ps1`](switch-frontend.ps1) | Windows 로컬 검증용입니다. 프론트엔드 전달 대상을 Blue/Green으로 바꾸고 설정 검사·reload·실제 요청 수신을 확인합니다. API 전달 대상은 유지합니다. |
| [`check-notification-schema.sh`](check-notification-schema.sh), [`check-notification-schema.sql`](check-notification-schema.sql) | 선택한 개발·운영 DB의 알림 스키마와 Flyway 이력을 읽기 전용으로 검사합니다. |
| [`check-privacy-schema.sh`](check-privacy-schema.sh), [`check-privacy-schema.sql`](check-privacy-schema.sql) | 개인정보 배포 전후 PRIMARY DB의 폐기된 변경 흔적·독립 Flyway 이력·신규 전용 테이블을 읽기 전용으로 검사합니다. [배포 순서](../docs/operations/database/privacy-deployment.md)를 따릅니다. |
| [`flyway-login-constraint-prepare.sql`](flyway-login-constraint-prepare.sql) | **DB 변경:** Flyway 이력이 없는 기존 DB에서 로그인 식별자 고유 제약의 이름을 바꿔 전환을 준비합니다. |
| [`flyway-login-constraint-finalize.sql`](flyway-login-constraint-finalize.sql) | **DB 변경:** 지정된 Flyway 마이그레이션 성공 후 중복된 기존 고유 제약을 제거합니다. |
| [`reset-restored-notifications.sql`](reset-restored-notifications.sql) | **데이터 삭제:** 격리된 복원 DB에서 알림 신청·이메일 설정·미완료 취소 요청을 비웁니다. |

## 사용 기준

### 로컬 프론트엔드 Blue/Green 검증

두 PowerShell 도구는 초기 구성이 끝난 Windows 로컬 환경에서 사용합니다.
개발·운영 서버의 Linux 배포 워크플로에는 연결되어 있지 않습니다.

- Docker 프로젝트 이름은 `2026-toadzip`이며, 프론트엔드 Blue·Green과 Edge Nginx가 준비되어 있어야 합니다.
- Edge Nginx는 `.local/edge-nginx-533-runtime/edge`를 `/etc/nginx/conf.d`에 읽기 전용으로 마운트해야 합니다. 확인 요청 주소는 `http://127.0.0.1:18080`입니다.
- 재생성 도구는 기존 로컬 이미지를 ID로 고정하고 `--no-build --pull never --no-deps`로 Green만 교체합니다. `-ComposeOverride`로 추가 Compose 파일을 지정할 수 있습니다.
- 재생성할 때는 아래처럼 공통 `deploy.lock`을 잡고 호출합니다. 전환 도구는 같은 잠금을 내부에서 사용합니다. 설정을 수동으로 바꾸거나 다른 배포 명령을 동시에 실행하지 않습니다.
- 전환 도구는 설정 변경 전에 백업하고, 변경 후 오류가 나면 이전 설정으로 복구를 시도합니다. 후보 기동 실패·복구와 진행 중 요청 완료의 검증은 별도입니다.
- 설정 백업·추가 시험 설정·요청 로그는 `.local/`에 보관하며 커밋하지 않습니다.

다음 예시는 저장소 루트에서 실행합니다. Green을 실제로 재생성한 뒤 화면을 Green으로 전환하고 Blue로 원복합니다. 사용할 이미지는 이미 로컬에 있어야 합니다.

```powershell
# 공통 잠금을 잡고 Green 프론트엔드 준비 후 전환·원복
& {
    $ErrorActionPreference = 'Stop'
    $runtime = (Resolve-Path '.local/edge-nginx-533-runtime').Path
    $lock = [IO.File]::Open(
        (Join-Path $runtime 'deploy.lock'),
        [IO.FileMode]::OpenOrCreate,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None
    )
    try {
        .\scripts\deploy-bluegreen.ps1 -Image 'toadzip-frontend:bg-local-533'
    }
    finally {
        $lock.Dispose()
    }

    # 화면 전달 대상을 Green으로 전환한 뒤 Blue로 원복
    .\scripts\switch-frontend.ps1 -Color green
    .\scripts\switch-frontend.ps1 -Color blue
}
```

### 공통 검사·DB 도구

검사 기준은 [기여 규칙](../CONTRIBUTING.md), DB 점검은 [알림 데이터 안내](../docs/operations/notifications.md), HTTPS 검증은 [HTTPS 가이드](../docs/operations/https.md)를 참고합니다. Flyway 전환·복원 SQL은 각 파일의 실행 전제와 대상 DB 확인 조건을 따릅니다. 복원 SQL은 기존 알림 자료를 삭제하므로 개인정보 신규 테이블의 배포·파기 절차로 사용하지 않습니다.

Flyway 검사는 저장소 루트에서 `python3 scripts/check-flyway-migrations.py --base-ref origin/develop`로 실행합니다.
`origin/develop`은 로컬에 가져온 기준이므로 최신 상태인지 확인합니다. 검사 대상은 작업 파일이 아닌 `HEAD`의 커밋입니다.
`db/migration`과 `db/privacy`는 이력이 독립적이므로 신규 `V` 버전을 각 경로의 develop 최신 `V` 버전과 비교합니다. 기존 baseline(`B`) SQL도 변경할 수 없고,
동일한 `B` 버전의 중복을 차단합니다. baseline과 일반(`V`) SQL이 같은 버전을 사용하는 기존 구조는 허용합니다.
백엔드 CI는 develop 대상 PR의 병합 결과를 해당 PR의 develop 기준 커밋과 비교합니다. 그 외 PR·배포 실행은 버전 중복을 검사합니다.
이후 develop이 변경되면 최신 기준으로 검사를 다시 통과하도록 PR을 갱신해야 합니다. 이 검사는 실제 DB의 적용 이력이나 SQL 실행 결과를 검증하지 않습니다.

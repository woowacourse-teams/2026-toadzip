# DB 백업과 30일 보관 운영

이 저장소에는 **개발 주 DB(`db-dev`), 운영 주 DB(`db-prod`), 공유 DB(`db-shared`)**를 매일 백업하는 스크립트가 있다. 각 DB는 S3의 `backups/<서비스명>/`에 별도 저장된다. DB 덤프에는 알림 이메일도 포함되므로 백업 버킷에 접근할 수 있는 사람을 제한한다. 지금은 AWS 계정·버킷이 확인되지 않아 **운영 설정은 아직 적용되지 않았다**.

## 어떤 정보를 확인하라는 뜻인가요?

| 항목 | 쉽게 설명하면 | 어디서 확인하나요? |
| --- | --- | --- |
| AWS 계정 ID | 서버 비용과 자원을 소유한 AWS 계정의 12자리 번호 | AWS 콘솔 오른쪽 위 계정 메뉴. 로그인 비밀번호나 액세스 키를 뜻하지 않는다. |
| 리전 | 서버가 놓인 물리적 지역 | DB 서버가 보이는 EC2 콘솔의 지역 선택. 서울은 `ap-northeast-2`이며 실제 DB가 있는 리전을 사용한다. |
| 전용 S3 버킷 | DB 백업 파일만 넣을 온라인 보관함 | 아래 자동 배포가 새로 만들고 이름을 출력한다. 기존 버킷을 찾거나 이름을 미리 정할 필요가 없다. |
| DB 서버 운영 계정 | Linux DB 서버에서 명령을 실행하는 사용자 이름 | DB 서버에 접속해 `whoami`. 예: `ubuntu`, `ec2-user`. 사이트 로그인이나 PostgreSQL 사용자와 다른 값이다. |
| DB EC2의 IAM 역할 | DB 서버가 AWS 백업 보관함에 접근할 수 있게 해 주는 권한 묶음 | EC2 → DB 인스턴스 → 보안 → IAM 역할. 아래 자동 배포는 기존 역할에 백업 권한을 추가한다. 역할이 없으면 인프라 담당자가 먼저 연결한다. |

웹 도메인은 개발 `dev.bokduckbang.com`, 운영 `bokduckbang.com`이다. 이 주소만으로 AWS 계정이나 DB 서버 운영 계정을 알 수는 없다. 위 정보는 DB 서버를 관리하는 팀원에게 확인하면 된다. 비밀값을 메신저나 저장소에 전달할 필요는 없다.

## 자동화 설치: 최초 한 번

사람이 매일 백업하거나 완료 로그를 읽을 필요가 없도록 **실행과 확인을 각각 자동화**한다.

1. DB 서버에서는 `systemd`가 매일 **03:10 한국 시간**에 세 DB를 백업한다. 작업이 실패하면 즉시 AWS SNS를 통해 **toadzip.official@gmail.com**으로 실패 메일을 보낸다.
2. AWS에서는 DB 서버와 독립된 Lambda가 **매시간** S3를 확인한다. 최근 덤프가 26시간 이내인지, 30일 보관·비공개·암호화가 유지되는지 검사한다.
3. CloudWatch는 두 번 연속 이상이 있거나 검사 결과가 오지 않으면 같은 Gmail로 알린다. 정상 상태로 돌아오면 복구 메일을 보낸다. DB 서버와 로컬 타이머가 멈춰도 AWS 감시가 남아 있다. 로컬 작업 실패는 즉시 알리지만, 서버 자체가 꺼진 경우에는 마지막 백업이 26시간을 넘긴 뒤 두 번의 시간별 검사에서 이상이 확인되면 알린다.

이 자동 메일은 **팀의 백업 장애 알림**이다. 사용자에게 보내는 주택 알림과 취소 코드는 계속 수동으로 보낸다. 자동 검사는 파일 생성과 저장·설정 이상을 확인하고, 실제 복구 가능성은 월별 복원 연습으로 확인한다.

AWS CLI와 Python 3가 있는 관리 환경에서 AWS 계정·리전·DB 인스턴스 역할을 확인한 뒤 다음 명령을 실행한다. 대문자 인자는 실제 값으로 바꾼다.

```bash
bash infra/db/deploy-backup-stack.sh AWS_ACCOUNT_ID REGION_CODE DB_EC2_ROLE_NAME
```

스크립트는 로그인한 AWS 계정 번호가 입력값과 같은지 확인한 뒤 CloudFormation으로 전용 버킷, 30일 보관·암호화·공개 차단, 서버 권한, 매시간 검사와 메일 경보를 함께 설치한다. 이 명령은 AWS 자원을 실제로 생성·변경하므로 확인된 계정에서 실행한다. 설치에 필요한 AWS 권한은 인프라 담당자가 사용한다. 기존 서버 역할을 교체하지 않고 백업에 필요한 권한을 추가한다. 출력의 `BackupBucketName`과 `AlertTopicArn`을 다음 설정에 쓴다.

공식 Gmail에 도착하는 **AWS SNS Subscription Confirmation**의 **Confirm subscription**을 한 번 눌러야 메일 수신이 활성화된다. [SNS 공식 안내](https://docs.aws.amazon.com/sns/latest/dg/sns-email-notifications.html)를 따른다. 그 후에는 문제가 있을 때와 복구할 때 자동으로 받는다.

DB 서버에서 저장소를 최신 버전으로 갱신하고 다음처럼 설정 파일을 만든다.

```bash
sudo install -d -m 755 /etc/toadzip
sudo install -m 600 infra/db/backup.env.example /etc/toadzip/db-backup.env
sudoedit /etc/toadzip/db-backup.env
```

버킷·계정 ID·리전·저장소 경로·SNS 토픽 주소를 입력한다. 파일은 root 소유로 유지한다. DB 암호는 기존 DB 환경 파일에만 둔다. 실제 Linux 계정을 확인한 뒤 설치 스크립트를 한 번 실행한다.

```bash
sudo bash infra/db/install-backup-automation.sh DB_SERVER_OS_USER
```

이 스크립트가 실행 계정과 설정을 확인하고 타이머를 설치·활성화한 뒤 첫 백업까지 실행한다. DB 서버에는 Docker Compose와 AWS CLI, GNU `date`·`stat`가 필요하며 실행 계정이 DB 환경 파일을 읽고 Docker를 사용할 수 있어야 한다. 이후 백업 실행과 상태 확인은 자동이다. 초기 설치와 SNS 이메일 본인 확인은 사람이 한 번 수행해야 한다. 계정과 서버 대상이 아직 확인되지 않아 현재 저장소에서는 이 설치 명령을 실행하지 않았다.

## 1. 운영 전에 확인할 것

1. DB가 실행되는 Linux 서버의 저장소 경로, Docker Compose 파일과 `infra/db/.env` 위치를 확인한다. DB 서비스 세 개가 실제로 이 Compose에서 실행되는지 확인한다.
2. 사용할 AWS 계정 ID, 리전, **백업만 담을 전용 S3 버킷**을 정한다. 기존 공용 버킷의 수명 주기 정책을 덮어쓰지 않는다.
3. 자동 설치는 DB 서버의 기존 EC2 역할에 버킷의 `ListBucket`, `GetObject`, `PutObject`, `AbortMultipartUpload`, `GetBucketVersioning`, `GetLifecycleConfiguration`, `GetBucketPublicAccessBlock`, `GetEncryptionConfiguration`과 해당 SNS 토픽의 `Publish` 권한을 부여한다. 수동 설치도 같은 권한이 필요하다. 장기 AWS 액세스 키는 서버에 두지 않는다.
4. DB 서버에 AWS CLI v2와 GNU `date`·`stat`, Bash, Docker Compose가 있어야 한다. 덤프 크기만큼의 **암호화된 임시 디스크** 공간이 필요하다. 스크립트는 임시 파일을 현재 계정만 읽게 만들고 종료 시 삭제한다.

`infra/db/backup.env.example`을 `/etc/toadzip/db-backup.env`로 복사해 버킷·계정 ID·리전·저장소 절대 경로를 채운다. DB 암호는 여기에 적지 않고 기존 `infra/db/.env`에만 둔다. 필요하면 `TOADZIP_DB_ENV_FILE`에 그 파일의 절대 경로를 쓴다. 이 파일은 DB 백업을 실행하는 OS 계정만 읽게 한다.

## 2. 기존 전용 버킷을 사용할 때의 수동 보관 정책 적용

위 CloudFormation 자동 배포를 사용했다면 이 절차는 생략한다. 이미 준비한 전용 버킷이 있을 때만 아래 절차를 사용한다. 이 경우 독립 AWS 감시 설치는 별도로 필요하다.

전용 버킷이 생성된 다음, **버킷 소유 AWS 계정과 이름을 먼저 확인**한다. 다음 명령은 해당 버킷의 설정을 바꾸므로 실제 대상이 확인된 뒤에만 실행한다. 새 버킷을 기준으로 하며 기존 수명 주기 규칙이 있다면 먼저 검토한다.

```bash
aws s3api head-bucket --bucket "$TOADZIP_BACKUP_BUCKET" --expected-bucket-owner "$TOADZIP_BACKUP_ACCOUNT_ID" --region "$AWS_REGION"
aws s3api get-bucket-versioning --bucket "$TOADZIP_BACKUP_BUCKET" --region "$AWS_REGION"
aws s3api get-bucket-lifecycle-configuration --bucket "$TOADZIP_BACKUP_BUCKET" --region "$AWS_REGION"
```

버전 관리는 **한 번도 켜지지 않은 상태**여야 한다. `Enabled` 또는 `Suspended`였다면 이전 버전이 30일 후에도 남을 수 있으므로 이 절차를 그대로 적용하지 않는다. 전용 버킷에서 다음 설정을 적용한다.
새 버킷의 `get-bucket-lifecycle-configuration`은 설정이 없다는 오류를 돌려줄 수 있다. 기존 규칙이 있으면 내용을 확인한 뒤 적용 여부를 결정한다.

```bash
aws s3api put-public-access-block --bucket "$TOADZIP_BACKUP_BUCKET" --region "$AWS_REGION" --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$TOADZIP_BACKUP_BUCKET" --region "$AWS_REGION" --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
aws s3api put-bucket-lifecycle-configuration --bucket "$TOADZIP_BACKUP_BUCKET" --region "$AWS_REGION" --lifecycle-configuration file://infra/db/backup-lifecycle.json
bash infra/db/check-backup-policy.sh
```

수명 주기 정책은 `backups/` 아래 객체를 생성 후 **30일**에 만료시키고 중단된 멀티파트 업로드는 7일 후 정리한다. S3의 실제 삭제는 비동기로 진행될 수 있다. 이 정책은 버킷 전체 설정을 교체하므로 전용 버킷에서만 적용한다.

## 3. 매일 실행과 확인

먼저 DB 서버에서 설정 파일을 불러오고 한 번 수동 실행한다. `backup-all.sh`는 세 DB를 차례로 덤프하고 각 아카이브를 `pg_restore --list`로 읽어 본 뒤 업로드한다. S3의 객체 크기와 로컬 덤프 크기도 비교한다. 마지막에 30일 정책·비공개·암호화와 각 DB의 최근 백업이 **26시간 이내**인지 검사한다. 어느 단계든 실패하면 종료 코드가 0이 아니다.

```bash
set -a
source /etc/toadzip/db-backup.env
set +a
bash infra/db/backup-all.sh
bash infra/db/check-backups.sh
```

Linux `systemd` 서버에서는 `infra/db/run-scheduled-backup.sh`를 `/usr/local/libexec/toadzip-db-backup-runner`로 설치한다. `infra/db/systemd/toadzip-db-backup.service.example`의 `User=`를 실제 DB 운영 OS 계정으로 바꾸어 `/etc/systemd/system/toadzip-db-backup.service`로 설치하고, `.timer`를 같은 위치에 설치한다. 이 계정은 Docker Compose 실행과 `/etc/toadzip/db-backup.env` 및 DB 환경 파일 읽기 권한이 필요하다. 타이머는 **매일 03:10 한국 시간(전날 18:10 UTC)**에 실행하며, 서버가 꺼져 놓친 실행은 재시작 시 보충한다.

```bash
sudo install -D -m 755 infra/db/run-scheduled-backup.sh /usr/local/libexec/toadzip-db-backup-runner
sudo install -m 644 infra/db/systemd/toadzip-db-backup.timer /etc/systemd/system/toadzip-db-backup.timer
sudo cp infra/db/systemd/toadzip-db-backup.service.example /etc/systemd/system/toadzip-db-backup.service
sudoedit /etc/systemd/system/toadzip-db-backup.service # User=를 실제 DB 운영 OS 계정으로 수정
sudo systemctl daemon-reload
sudo systemctl enable --now toadzip-db-backup.timer
sudo systemctl start toadzip-db-backup.service
systemctl list-timers toadzip-db-backup.timer
systemctl status toadzip-db-backup.service
journalctl -u toadzip-db-backup.service -n 50 --no-pager
```

자동 설치를 사용하면 서비스 실패와 AWS의 독립 검사 이상은 공식 Gmail로 전달된다. 설치 직후에는 첫 백업과 다음날 자동 백업을 한 번 확인하고, 평소에는 장애 메일이 왔을 때 확인한다. 한 달에 한 번은 아래 복원 연습을 한다. **덤프를 읽는 검사와 업로드 크기 확인만으로 실제 복원을 보장하지는 않는다.**

## 4. 복원 연습과 실제 복구

1. 서비스와 알림 대상 목록 사용을 중지한다. 백업을 격리된 PostgreSQL의 비어 있는 `toadzip_restore` DB로 내려받아 `pg_restore --exit-on-error --no-owner --no-acl -d toadzip_restore <archive>`로 복원한다. 운영 DB에 직접 복원하지 않는다.
2. 백업의 스키마가 현재 알림 테이블보다 오래됐다면 격리된 DB에서 먼저 Flyway를 적용한다. 애플리케이션이 이 DB를 조회하거나 알림 대상 목록을 사용하도록 연결하지 않는다.
3. `psql -X -v ON_ERROR_STOP=1 -d toadzip_restore -f scripts/reset-restored-notifications.sql`을 실행한다. 스크립트는 DB 이름을 확인한 다음 알림 신청·알림 이메일·미완료 취소 요청을 삭제한다. 끝의 다섯 개 숫자가 모두 **0**이어야 한다. 계정 자체의 이메일은 보존한다.
4. 복원 결과와 알림 데이터 0건을 기록한 뒤에만 서비스 전환을 검토한다. 기존 신청은 재신청을 안내한다. 공유 DB 백업도 별도 격리 DB에 복원하여 확인한다.

백업 시점 이후에 취소한 신청은 예전 백업 안에 남아 있다. 그래서 **복원 후 알림 데이터를 비우는 단계가 필수**다. 이 방식은 복구 후 기존 알림 신청을 유지할 수 없는 대신, 취소한 사용자에게 다시 알림을 보내는 일을 막는다. 하루 한 번 백업이므로 마지막 성공 백업 이후 최대 약 24시간의 데이터는 복원되지 않을 수 있다. 복구 소요 시간은 월별 연습에서 측정한다.

## 적용 완료 판정

- AWS 계정·버킷·도메인이 확인되어 있고 버킷 정책 검사에 성공한다.
- `systemctl list-timers`에 타이머가 활성화되어 있다.
- 첫 수동 실행과 다음날 자동 실행 모두 세 DB 백업의 `Backup fresh`가 나온다.
- SNS 메일 수신 확인을 완료하고 AWS Lambda 검사와 네 개 CloudWatch 경보가 정상이다. 로컬 작업 실패, 오래된/없는 백업, 버킷 정책 이상, 검사 결과 누락이 감시 대상이다.
- 30일 이후 오래된 백업 객체가 삭제되는지 S3에서 확인하고 월별 격리 복원 연습이 성공한다.

현재 저장소 변경만으로 이 항목들이 실제 운영에 완료된 것으로 보지 않는다.

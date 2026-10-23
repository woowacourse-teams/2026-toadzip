# 로컬 DB 초기화 후 다시 실행

모든 명령은 **PowerShell**에서 실행함. Docker Desktop이 `Engine running`인 상태여야 함.

이 절차는 `2026-toadzip` 프로젝트의 **기본 DB와 공유 DB를 모두 삭제함**. 관리자 계정, 수집 원본, 좌표, 단지·공고, 실행·실패 이력 등 두 DB에 저장된 데이터가 모두 사라짐. 필요한 데이터가 있다면 먼저 백업해야 함. 수집·정제·업로드가 진행 중이면 완료된 뒤 진행함.

## 1. 저장소로 이동하고 대상 확인

```powershell
cd C:\Users\minse\Documents\GitHub\2026-toadzip
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml ps --all
docker volume ls --filter label=com.docker.compose.project=2026-toadzip --format "{{.Name}}"
```

삭제 대상은 아래 두 볼륨임. 다른 프로젝트나 별도 이름의 볼륨은 삭제하지 않음.

- `2026-toadzip_postgres_data`: 기본 DB
- `2026-toadzip_postgres_shared_data`: 공유 DB

## 2. 앱을 중지하고 DB 삭제

```powershell
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml stop frontend backend db db-shared
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml rm -f frontend backend db db-shared
docker volume rm 2026-toadzip_postgres_data 2026-toadzip_postgres_shared_data
```

첫 명령은 앱을 중지하고, 두 번째는 중지된 컨테이너를 제거함. **세 번째 명령에서 DB 데이터가 삭제됨.** 이미지와 저장소 코드, `.env`, 모니터링 볼륨은 유지됨.

명령이 실패하면 다음 단계로 넘어가지 않고 원인을 확인함. `volume is in use`가 나오면 다른 컨테이너가 해당 볼륨을 사용 중이라는 뜻임. 강제 삭제하지 않음.

## 3. 관리자 계정 재생성 설정

저장소 루트의 `.env`를 열어 아래 값을 설정하고 저장함. DB 연결·비밀번호 등 기존 설정은 유지함. 아래 값은 예시이므로 실제 사용할 값으로 바꿔야 함.

```dotenv
ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_LOGIN_IDENTIFIER=사용할_관리자_아이디
ADMIN_PASSWORD=사용할_관리자_비밀번호
DATA_GO_KR_SERVICE_KEY=공공데이터포털_API_키
```

단지 원천 수집은 서버의 `DATA_GO_KR_SERVICE_KEY`를 사용함. 단지 수집 화면에서는 API 키를 입력하지 않음. 키와 비밀번호가 들어 있는 `.env`는 Git에 올리거나 공유하지 않음.

## 4. 빌드하고 실행

```powershell
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml up -d --build --wait db db-shared backend frontend
```

빈 DB와 테이블이 준비되고 관리자 계정이 생성됨. 완료되면 [로컬 관리자 페이지](http://localhost/admin)에 접속해 설정한 계정으로 로그인함. `FRONTEND_PORT`를 변경했다면 해당 포트로 접속함.

이 방식에서는 프론트엔드도 Docker에서 실행하므로 `cd frontend`와 `npm run dev`를 별도로 실행할 필요 없음. `localhost:5173`은 별도로 실행한 개발 서버의 주소이므로 Docker 화면을 확인할 때는 `http://localhost/admin`을 사용함.

## 5. 관리자 자동 생성 끄기

로그인이 확인되면 `.env`를 수정하고 저장함.

```dotenv
ADMIN_BOOTSTRAP_ENABLED=false
```

변경한 환경변수를 백엔드에 반영함. 기존 계정과 DB 데이터는 유지됨.

```powershell
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml up -d --no-deps --force-recreate --wait backend
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml exec backend printenv ADMIN_BOOTSTRAP_ENABLED
```

마지막 명령의 결과가 `false`면 정상임. 백엔드 재시작으로 로그인이 풀리면 같은 계정으로 다시 로그인함.

## 평소 실행·종료·업데이트

DB 초기화는 매번 할 필요 없음. `.env`의 `ADMIN_BOOTSTRAP_ENABLED=false`를 유지함.

평소 실행:

```powershell
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml up -d --wait db db-shared backend frontend
```

코드 수정·업데이트 후 반영:

```powershell
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml up -d --build --wait db db-shared backend frontend
```

Git의 push·pull은 소스 코드를 옮기는 작업임. Docker 이미지는 빌드 시점의 코드를 담으므로 변경된 코드를 실행하려면 `--build`로 이미지를 다시 만들고 컨테이너에 반영해야 함.

상태 확인:

```powershell
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml ps
```

네 서비스가 실행 중이고 `healthy`이면 기동 검사를 통과한 상태임. 로그인·수집 등 개별 기능의 성공까지 의미하지는 않음.

데이터를 유지하고 종료:

```powershell
docker compose -p 2026-toadzip -f compose.yaml -f compose.local.yaml stop frontend backend db db-shared
```

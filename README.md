# Toadzip

## 프로젝트 구조

```text
toadzip/
├── compose.yaml    # 백엔드와 PostgreSQL 실행 설정
├── .env.example    # 환경변수 예시
├── backend/        # Spring Boot 백엔드와 Dockerfile
└── frontend/       # 프런트엔드 자리 표시자
```

## 개발 환경

- JDK 25
- Git

Gradle은 별도로 설치하지 않습니다. 저장소에 포함된 Gradle Wrapper를 사용합니다.

설치된 Java 버전을 확인합니다.

```shell
java -version
```

## Docker로 실행

### 사전 준비

- Git
- Docker Engine 또는 Docker Desktop
- Docker Compose

Docker Desktop에는 Docker Compose가 포함되어 있습니다.

### 환경변수 준비

프로젝트 루트에서 예시 파일을 복사합니다.

```shell
cp .env.example .env
```

생성된 `.env`에서 PostgreSQL 비밀번호를 변경합니다. Git에 커밋하지 않습니다.

### 빌드 및 실행

프로젝트 루트에서 다음 명령을 실행합니다.

```shell
docker compose up -d --build
```

이 명령은 백엔드 이미지를 빌드하고 PostgreSQL과 백엔드 컨테이너를 함께 실행합니다.

### 실행 확인

```shell
docker compose ps
docker compose logs -f backend
```

헬스체크 API를 호출해 백엔드 상태를 확인할 수 있습니다.

```shell
curl http://localhost:8080/api/health
```

정상 응답:

```json
{"status":"UP"}
```

### 종료

```shell
docker compose down
```

`docker compose down`을 실행해도 PostgreSQL 데이터 볼륨은 유지됩니다.

## IntelliJ IDEA 설정

`backend`를 Gradle 프로젝트로 불러오고 Gradle JVM을 JDK 25로 설정합니다. `build.gradle`이 변경되면 Gradle 도구 창에서 **Reload All Gradle Projects**를 실행해야 새 의존성이 IDE 실행 클래스패스에 반영됩니다.

애플리케이션을 IDE에서 직접 실행했을 때 데이터소스 드라이버 오류가 발생하면 먼저 Gradle 프로젝트를 다시 불러옵니다. 기준 실행 방법은 Gradle Wrapper를 사용하는 `bootRun`입니다.

## macOS 또는 Linux에서 실행

```shell
cd backend
./gradlew test
./gradlew bootRun
```

전체 빌드를 실행하려면 다음 명령을 사용합니다.

```shell
cd backend
./gradlew build
```

## Windows PowerShell에서 실행

```powershell
Set-Location backend
.\gradlew.bat test
.\gradlew.bat bootRun
```

전체 빌드를 실행하려면 다음 명령을 사용합니다.

```powershell
Set-Location backend
.\gradlew.bat build
```

## 환경 설정

로컬 실행과 테스트에서는 인메모리 H2 데이터베이스를 사용하므로 별도의 데이터베이스나 환경변수가 필요하지 않습니다. H2는 개발 및 테스트 클래스패스에만 포함되며 운영 JAR에는 포함되지 않습니다.

운영 환경에서는 PostgreSQL 연결 정보를 환경변수 또는 배포 환경의 비밀 저장소로 주입해야 합니다. 실제 비밀값은 `.env` 등에 저장하고 커밋하지 않습니다.

## 지속적 통합

GitHub Actions는 Ubuntu와 Windows에서 각각 Gradle 전체 빌드를 실행합니다. `build` 작업에는 단위 테스트가 포함됩니다.

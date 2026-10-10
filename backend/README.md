# 백엔드

공공주택·공고 조회, 외부 데이터 수집·정제, 사용자·관리자 인증과 알림 신청을 처리하는 Java·Spring Boot 애플리케이션입니다.

## 디렉터리 구조

| 디렉터리 | 역할 |
| --- | --- |
| [src/](src/) | 애플리케이션 코드·설정과 테스트를 관리합니다. |
| [docs/](docs/README.md) | 개발 기준, 계층·API 규칙, 데이터 수집과 운영 절차를 설명합니다. |
| [gradle/](gradle/) | Gradle Wrapper의 배포 버전 설정과 실행 라이브러리를 담습니다. |

### 소스 구성

| 디렉터리 | 역할 |
| --- | --- |
| [src/main/java/](src/main/java/) | 실행 진입점과 주택·공고·수집·알림 등 기능별 코드를 담습니다. |
| [src/main/resources/](src/main/resources/) | 환경별 애플리케이션 설정, 로깅 설정과 Flyway 마이그레이션 SQL을 담습니다. |
| [src/test/java/](src/test/java/) | 기능별 단위·웹·DB 통합 테스트를 담습니다. |
| [src/test/resources/](src/test/resources/) | 테스트 환경 설정과 외부 API 응답·공고 문서 등의 테스트 자료를 담습니다. |

## 주요 파일

| 파일 | 역할 |
| --- | --- |
| [build.gradle](build.gradle) | 플러그인, Java 버전, 의존성과 테스트 실행 설정을 정의합니다. |
| [settings.gradle](settings.gradle) | Gradle 프로젝트 이름을 정의합니다. |
| [gradlew](gradlew), [gradlew.bat](gradlew.bat) | 저장소에 지정된 Gradle 버전으로 명령을 실행합니다. |
| [Dockerfile](Dockerfile) | 애플리케이션 JAR을 빌드하고 실행 이미지를 만듭니다. |
| [AGENTS.md](AGENTS.md), [CODE_CONVENTION.md](CODE_CONVENTION.md) | 백엔드 작업 지침과 Java 코드 규칙을 안내합니다. |
| [.dockerignore](.dockerignore), [.gitignore](.gitignore), [.gitattributes](.gitattributes) | 이미지 빌드·Git 추적 제외 대상과 파일 속성을 정의합니다. |

실행은 [환경별 설정](../docs/SETUP.md), 개발·운영 상세는 [백엔드 문서 목록](docs/README.md),
기여와 검증 절차는 [기여 가이드](../CONTRIBUTING.md)를 참고합니다.

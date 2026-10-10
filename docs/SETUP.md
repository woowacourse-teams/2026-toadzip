# 환경별 설정

## 개인정보 기능 배포

개인정보 기능을 포함한 첫 릴리스에는 [개인정보 DB 배포](../backend/docs/privacy-deployment.md)의
사전 검사 → 백엔드 기동 → 사후 검사 → 프론트 기동 순서를 사용한다. 아래의 일반적인 일괄
재배포 명령으로 대체하지 않는다. 분리 PR 네 개를 통합한 같은 릴리스가 필요하며 중간 PR을
개별 배포하지 않는다. 서버 외부 자동 배포 유무는 병합 전에 운영자가 별도로 확인한다.

## 사전 준비

- Git
- Docker
- Docker Compose
- Docker Buildx

- [로컬 환경](LOCAL_SETUP.md)
- [개발 서버](DEV_SERVER_SETUP.md)
- [운영 서버](PROD_SERVER_SETUP.md)
- [DB 서버](../infra/db/SETUP.md)
- [모니터링 서버](MONITORING_SERVER_SETUP.md)

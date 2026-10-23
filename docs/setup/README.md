# 환경 설정

앱과 서버를 처음 준비하는 안내다. 명령과 상대 파일 경로는 문서 폴더가 아니라 저장소 루트를 기준으로 한다. 컨테이너 실행에는 Git, Docker, Docker Compose와 Buildx가 필요하다.

## 문서 안내

| 문서 | 다루는 내용 |
|---|---|
| [로컬 환경](local.md) | 앱·DB의 기본 실행과 선택적인 로그·메트릭 도구 |
| [개발·운영 서버](servers.md) | 서버별 환경값·DB 포트·최초 실행과 HTTPS 연결 |

## 관련 문서

- 프론트엔드 단독 실행은 [프론트 README](../../frontend/README.md)를 따른다.
- DB·모니터링 서버 준비는 [DB 서버](../operations/database/server.md)와 [모니터링 운영](../operations/monitoring.md)을 따른다.
- 첨부파일의 크기·동시 전송·임시 디스크 조건과 배포 후 확인은 [첨부파일 API](../features/documents/attachments.md#설정과-배포-확인), PDF·HWP 구현·미지원 조건은 [문서 뷰어](../features/documents/viewers.md)를 따른다.

## 개인정보 기능 배포

개인정보 기능을 포함한 첫 릴리스에는 [개인정보 DB 배포](../operations/database/privacy-deployment.md)의
사전 검사 → 백엔드 기동 → 사후 검사 → 프론트 기동 순서를 사용한다. 일반 일괄
재배포로 대체하지 않으며, 계약이 맞는 백엔드·프론트를 같은 완성 릴리스로 준비한다.
현재 develop·main 푸시는 자동 배포를 실행하므로 특별 배포가 필요하면 병합 전에
[배포 운영](../operations/deployment.md)의 정비 절차와 저장소 밖 자동화를 함께 확인한다.

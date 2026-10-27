# DB 운영

DB 서버를 준비하고 Flyway를 도입·전환하거나 기존 스키마와 개인정보 배포 조건을 점검하는 절차를 설명한다.

## 문서 안내

| 문서 | 다루는 내용 |
|---|---|
| [DB 서버](server.md) | 개발·운영·공유 DB의 구성·환경값과 호스트·DB 메트릭 |
| [Flyway 도입](flyway.md) | 빈 DB와 기존 DB의 적용 경로·baseline·사전 확인 |
| [기존 수집 DB 전환](ingest-upgrade.md) | 기존 ingest 브랜치 DB의 통합 업그레이드 조건·검증 |
| [개인정보 DB 배포](privacy-deployment.md) | 완성 릴리스, writer 중지·검사·기동·사후 확인과 복구 |
| [과거 스키마 점검](legacy-schema-notes.md) | 당시 스키마 대조 기록. 현재 환경 적용 여부는 별도 확인 |

## 관련 문서

일반 배포와 특별 DB 전환의 차이는 [배포 운영](../deployment.md), 코드의 트랜잭션·마이그레이션 기준은 [백엔드 영속성](../../development/backend/persistence.md)을 따른다. 실제 DB 상태·승인을 확인하고 대상 문서의 전제와 순서를 적용한다.

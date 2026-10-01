# 기존 DB의 ingest·develop 통합 업그레이드

낮은 버전의 미적용 마이그레이션이 남은 아래 DB가 대상이다.

| 기존 DB | 적용된 최종 버전 | 보충할 낮은 버전 |
|---|---|---|
| 143·scheduler-removal 작업 DB | `20260930.01` | `20260928.03`, `20260928.04`, `20260929.01` |
| 최신 develop DB | `20260930.09` | `20260930.01` |

새 DB와 모든 버전이 적용된 DB는 일반 시작 절차를 사용한다.

## 적용 순서

1. 대상 DB 이름·백업을 확인하고 [기존 ingest 종료·서버 교체 조건](data-pipeline-execution.md)을 따른다.
   아래 이력에서 적용된 버전과 보충할 낮은 버전이 위 표에 맞는지 확인한다.

   ```sql
   SELECT current_database();
   SELECT installed_rank, version, script, success
   FROM flyway_schema_history ORDER BY installed_rank;
   ```

2. 현재 통합 아티팩트의 기존 서버 시작 명령에 아래 인자를 **이번 기동에만** 붙인다.
   누락된 낮은 버전과 최신 알림 스키마를 적용하며 현재 통합 버전보다 높은 버전은 적용하지 않는다.

   ```text
   --spring.flyway.out-of-order=true --spring.flyway.target=20260930.09
   ```

3. 보충한 버전의 `success=true`, 공급 승인·일정·통합 실행 유형·알림 스키마와
   Hibernate `validate` 기동 성공을 확인한다. 기존 실행 이력이 유지되는지도 확인한다.
4. 두 인자를 제거하고 기본 설정으로 다시 시작한다. Flyway 검증이 통과하고 추가 적용이 없어야 한다.
   저장소의 기본 설정에는 `out-of-order`를 추가하지 않았다.

`LocalProfileSchemaPersistenceTest`는 작업 DB 18개·최신 develop DB 28개 → 통합 29개 이력,
기본 설정의 누락 검증 오류, 위 두 인자의 1회 기동과 기본 설정의 재기동·재적용 0건을 검증한다.
기존 실행 이력 보존과 보충 후 `COMPLEX_SYNC` 저장도 확인한다.
운영 DB에 적용한 기록은 아니며, 이미 적용된 SQL·버전·체크섬은 변경하지 않는다.

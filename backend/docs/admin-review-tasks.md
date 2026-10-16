# 관리자 검토 업무

- 단지·공고별 담당자, 한국 날짜의 기한, 대기·진행·보류·완료 상태와 메모를 공유한다.
- 업무 완료는 데이터 검증 완료가 아니다. 등록값·확인 스냅샷을 변경하지 않는다.
- GET/PUT `/api/admin/quality/tasks/{resource}/{id}`: 조회·저장. `resource`는 `COMPLEX`·`ANNOUNCEMENT`.
- PUT은 `expectedRevision`(신규 0), `assignee`(빈 값은 미배정), `dueDate`(없으면 null), `status`, `note`를 받는다.
- 실제 관리자만 배정할 수 있다. 저장 충돌은 409이며 최신 업무와 현재 입력을 비교한 뒤 다시 저장한다.
- GET `/api/admin/quality/tasks`: `mine`(기본 true), `state`, `overdue`, `page`, `size` 필터.
- GET `/api/admin/quality/tasks/alerts`: 내 담당의 미완료 업무 중 기한 초과 또는 확인한 원천 변경·소실.
- 기한 당일은 초과가 아니다. 판정 시계는 `Asia/Seoul`이며 완료 업무는 알림에서 제외한다.
- 원천 변경은 검토 목록과 같은 정규화 SQL로 판정한다. 최초 원천 확인 전에는 감지하지 않는다.
- GET `/api/admin/quality/tasks/assignees`: 담당자 선택용 관리자 식별자만 반환한다.
- 관리자 권한·CSRF 정책을 적용하며 변경 전후, 작성자와 시각을 수정 이력에 보관한다.
- `V20261016_04`는 업무 테이블만 추가하며 이전 앱과 공존한다. 데이터 자동 수정은 없다.
- `ReviewTaskIntegrationTest`: revision 충돌, 권한·CSRF, 한국 날짜 경계, 담당자 분리, 원천 소실, 완료 제외.

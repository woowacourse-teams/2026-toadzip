-- 기관별 수집 실행에 SH·GH 정제 단계를 연결한다. 기존 실행 이력의 단계는 유지한다.
ALTER TABLE data_pipeline_executions DROP CONSTRAINT IF EXISTS data_pipeline_executions_current_step_check;
ALTER TABLE data_pipeline_executions ADD CONSTRAINT data_pipeline_executions_current_step_check
    CHECK (current_step IN (
        'COLLECT_MYHOME_COMPLEXES',
        'COLLECT_LH_LEASE_CATALOG',
        'MAP_MYHOME_COMPLEXES',
        'ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS',
        'COLLECT_MYHOME_ANNOUNCEMENTS',
        'COLLECT_LH_ANNOUNCEMENT_CATALOG',
        'COLLECT_LH_ANNOUNCEMENT_SUPPLIES',
        'COLLECT_LH_ANNOUNCEMENT_DETAILS',
        'MAP_MYHOME_ANNOUNCEMENTS',
        'ENRICH_LH_ANNOUNCEMENTS',
        'COLLECT_SH_ANNOUNCEMENTS',
        'MAP_LH_ANNOUNCEMENTS',
        'COLLECT_GH_ANNOUNCEMENTS',
        'MAP_SH_ANNOUNCEMENTS',
        'MAP_GH_ANNOUNCEMENTS'
    ));

ALTER TABLE data_pipeline_executions DROP CONSTRAINT IF EXISTS data_pipeline_executions_failed_step_check;
ALTER TABLE data_pipeline_executions ADD CONSTRAINT data_pipeline_executions_failed_step_check
    CHECK (failed_step IN (
        'COLLECT_MYHOME_COMPLEXES',
        'COLLECT_LH_LEASE_CATALOG',
        'MAP_MYHOME_COMPLEXES',
        'ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS',
        'COLLECT_MYHOME_ANNOUNCEMENTS',
        'COLLECT_LH_ANNOUNCEMENT_CATALOG',
        'COLLECT_LH_ANNOUNCEMENT_SUPPLIES',
        'COLLECT_LH_ANNOUNCEMENT_DETAILS',
        'MAP_MYHOME_ANNOUNCEMENTS',
        'ENRICH_LH_ANNOUNCEMENTS',
        'COLLECT_SH_ANNOUNCEMENTS',
        'MAP_LH_ANNOUNCEMENTS',
        'COLLECT_GH_ANNOUNCEMENTS',
        'MAP_SH_ANNOUNCEMENTS',
        'MAP_GH_ANNOUNCEMENTS'
    ));

ALTER TABLE data_pipeline_execution_completed_steps DROP CONSTRAINT IF EXISTS data_pipeline_execution_completed_steps_completed_step_check;
ALTER TABLE data_pipeline_execution_completed_steps ADD CONSTRAINT data_pipeline_execution_completed_steps_completed_step_check
    CHECK (completed_step IN (
        'COLLECT_MYHOME_COMPLEXES',
        'COLLECT_LH_LEASE_CATALOG',
        'MAP_MYHOME_COMPLEXES',
        'ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS',
        'COLLECT_MYHOME_ANNOUNCEMENTS',
        'COLLECT_LH_ANNOUNCEMENT_CATALOG',
        'COLLECT_LH_ANNOUNCEMENT_SUPPLIES',
        'COLLECT_LH_ANNOUNCEMENT_DETAILS',
        'MAP_MYHOME_ANNOUNCEMENTS',
        'ENRICH_LH_ANNOUNCEMENTS',
        'COLLECT_SH_ANNOUNCEMENTS',
        'MAP_LH_ANNOUNCEMENTS',
        'COLLECT_GH_ANNOUNCEMENTS',
        'MAP_SH_ANNOUNCEMENTS',
        'MAP_GH_ANNOUNCEMENTS'
    ));

ALTER TABLE data_pipeline_execution_skipped_steps DROP CONSTRAINT IF EXISTS data_pipeline_execution_skipped_steps_skipped_step_check;
ALTER TABLE data_pipeline_execution_skipped_steps ADD CONSTRAINT data_pipeline_execution_skipped_steps_skipped_step_check
    CHECK (skipped_step IN (
        'COLLECT_MYHOME_COMPLEXES',
        'COLLECT_LH_LEASE_CATALOG',
        'MAP_MYHOME_COMPLEXES',
        'ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS',
        'COLLECT_MYHOME_ANNOUNCEMENTS',
        'COLLECT_LH_ANNOUNCEMENT_CATALOG',
        'COLLECT_LH_ANNOUNCEMENT_SUPPLIES',
        'COLLECT_LH_ANNOUNCEMENT_DETAILS',
        'MAP_MYHOME_ANNOUNCEMENTS',
        'ENRICH_LH_ANNOUNCEMENTS',
        'COLLECT_SH_ANNOUNCEMENTS',
        'MAP_LH_ANNOUNCEMENTS',
        'COLLECT_GH_ANNOUNCEMENTS',
        'MAP_SH_ANNOUNCEMENTS',
        'MAP_GH_ANNOUNCEMENTS'
    ));

ALTER TABLE data_pipeline_execution_partial_failures DROP CONSTRAINT IF EXISTS data_pipeline_execution_partial_fai_partially_failed_step_check;
ALTER TABLE data_pipeline_execution_partial_failures ADD CONSTRAINT data_pipeline_execution_partial_fai_partially_failed_step_check
    CHECK (partially_failed_step IN (
        'COLLECT_MYHOME_COMPLEXES',
        'COLLECT_LH_LEASE_CATALOG',
        'MAP_MYHOME_COMPLEXES',
        'ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS',
        'COLLECT_MYHOME_ANNOUNCEMENTS',
        'COLLECT_LH_ANNOUNCEMENT_CATALOG',
        'COLLECT_LH_ANNOUNCEMENT_SUPPLIES',
        'COLLECT_LH_ANNOUNCEMENT_DETAILS',
        'MAP_MYHOME_ANNOUNCEMENTS',
        'ENRICH_LH_ANNOUNCEMENTS',
        'COLLECT_SH_ANNOUNCEMENTS',
        'MAP_LH_ANNOUNCEMENTS',
        'COLLECT_GH_ANNOUNCEMENTS',
        'MAP_SH_ANNOUNCEMENTS',
        'MAP_GH_ANNOUNCEMENTS'
    ));

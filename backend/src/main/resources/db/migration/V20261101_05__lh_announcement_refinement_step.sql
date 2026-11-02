-- 공고 정제를 LH 공고 정제(MAP_LH_ANNOUNCEMENTS) 한 단계로 바꾼다. 지난 실행 이력의 옛 단계 이름도 읽을 수 있게 남긴다.
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
        'MAP_LH_ANNOUNCEMENTS'
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
        'MAP_LH_ANNOUNCEMENTS'
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
        'MAP_LH_ANNOUNCEMENTS'
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
        'MAP_LH_ANNOUNCEMENTS'
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
        'MAP_LH_ANNOUNCEMENTS'
    ));

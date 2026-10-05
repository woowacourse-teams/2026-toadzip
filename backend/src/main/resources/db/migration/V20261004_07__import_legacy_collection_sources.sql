-- Import preserves known actual collection times. The migration time describes only the import operation.
-- Invalid identity and unknown LH conditions remain in the original tables for read-only fallback.
CREATE TABLE source_legacy_import_report (
    source VARCHAR(40) PRIMARY KEY,
    legacy_row_count BIGINT NOT NULL,
    imported_row_count BIGINT NOT NULL,
    preserved_legacy_row_count BIGINT NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE lh_announcement_catalog_entries ALTER COLUMN query_start_date DROP NOT NULL;
ALTER TABLE lh_announcement_catalog_entries ALTER COLUMN query_end_date DROP NOT NULL;

SELECT setval(pg_get_serial_sequence('myhome_complex_source_rows', 'id'), GREATEST(COALESCE((SELECT MAX(id) FROM myhome_complex_source_rows), 0), COALESCE((SELECT MAX(id) FROM myhome_complex_source), 0), 1), true);

SELECT setval(pg_get_serial_sequence('myhome_announcement_source_rows', 'id'), GREATEST(COALESCE((SELECT MAX(id) FROM myhome_announcement_source_rows), 0), COALESCE((SELECT MAX(id) FROM myhome_announcement_source), 0), 1), true);

SELECT setval(pg_get_serial_sequence('lh_lease_catalog_source_rows', 'id'), GREATEST(COALESCE((SELECT MAX(id) FROM lh_lease_catalog_source_rows), 0), COALESCE((SELECT MAX(id) FROM lh_catalog_source), 0), 1), true);

SELECT setval(pg_get_serial_sequence('lh_announcement_supply_rows', 'id'), GREATEST(COALESCE((SELECT MAX(id) FROM lh_announcement_supply_rows), 0), COALESCE((SELECT MAX(id) FROM lh_announcement_supply_source), 0), 1), true);

SELECT setval(pg_get_serial_sequence('lh_announcement_detail_rows', 'id'), GREATEST(COALESCE((SELECT MAX(id) FROM lh_announcement_detail_rows), 0), COALESCE((SELECT MAX(id) FROM lh_announcement_detail_source), 0), 1), true);

CREATE TEMP TABLE legacy_complex_rows ON COMMIT DROP AS
SELECT old.* FROM myhome_complex_source old
WHERE old.hsmp_sn > 0 AND old.brtc_code ~ '^[0-9]{2}$' AND old.signgu_code ~ '^[0-9]{3}$'
    AND NOT EXISTS (SELECT 1 FROM myhome_complex_source other WHERE other.hsmp_sn = old.hsmp_sn
        AND (other.brtc_code IS DISTINCT FROM old.brtc_code OR other.signgu_code IS DISTINCT FROM old.signgu_code))
    AND NOT EXISTS (SELECT 1 FROM myhome_complex_source invalid
        WHERE invalid.brtc_code IS NOT DISTINCT FROM old.brtc_code
            AND invalid.signgu_code IS NOT DISTINCT FROM old.signgu_code
            AND (invalid.hsmp_sn IS NULL OR invalid.hsmp_sn <= 0
                OR EXISTS (SELECT 1 FROM myhome_complex_source_bundles existing
                    WHERE existing.hsmp_sn = invalid.hsmp_sn) OR EXISTS (
                SELECT 1 FROM myhome_complex_source conflict WHERE conflict.hsmp_sn = invalid.hsmp_sn
                    AND (conflict.brtc_code IS DISTINCT FROM invalid.brtc_code
                        OR conflict.signgu_code IS DISTINCT FROM invalid.signgu_code))))
    AND NOT EXISTS (SELECT 1 FROM myhome_complex_source_regions region
        WHERE region.province_code = old.brtc_code AND region.district_code = old.signgu_code)
    AND NOT EXISTS (SELECT 1 FROM myhome_complex_source_bundles bundle WHERE bundle.hsmp_sn = old.hsmp_sn);
CREATE TEMP TABLE legacy_complex_regions ON COMMIT DROP AS
SELECT brtc_code, signgu_code, gen_random_uuid() record_id, COUNT(*) row_count,
    CASE WHEN COUNT(collected_at) = COUNT(*) AND MIN(collected_at) = MAX(collected_at)
        THEN MAX(collected_at) END collected_at
FROM legacy_complex_rows GROUP BY brtc_code, signgu_code;

INSERT INTO source_collection_records
    (id, version, source, started_at, finished_at, status, stored_row_count)
SELECT record_id, 0, 'MYHOME_COMPLEX', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'IMPORTED', row_count FROM legacy_complex_regions;
INSERT INTO source_collection_record_parameters(record_id, parameter_name, parameter_value)
SELECT record_id, 'LEGACY_TABLE', 'myhome_complex_source' FROM legacy_complex_regions;

INSERT INTO myhome_complex_source_regions(version, province_code, district_code, collected_at,
    last_collection_record_id)
SELECT 0, brtc_code, signgu_code, collected_at, record_id FROM legacy_complex_regions;
INSERT INTO myhome_complex_source_bundles(version, hsmp_sn, region_id)
SELECT 0, old.hsmp_sn, region.id FROM legacy_complex_rows old
JOIN myhome_complex_source_regions region ON region.province_code = old.brtc_code
    AND region.district_code = old.signgu_code GROUP BY old.hsmp_sn, region.id;

INSERT INTO myhome_complex_source_rows(source_id, source_order, collected_at, hsmp_sn, instt_nm, brtc_code, brtc_nm, signgu_code, signgu_nm, hsmp_nm, rn_adres, pnu, compet_de, hshld_co, suply_ty_nm, style_nm, suply_prvuse_ar, suply_cmnuse_ar, house_ty_nm, heat_mthd_detail_nm, buld_stle_nm, elvtr_instl_at_nm, parkng_co, bass_rent_gtn, bass_mt_rntchrg, bass_cnvrs_gtn_lmt)
SELECT bundle.id, ROW_NUMBER() OVER (PARTITION BY bundle.id ORDER BY old.id) - 1,
    old.collected_at, old.hsmp_sn, old.instt_nm, old.brtc_code, old.brtc_nm, old.signgu_code, old.signgu_nm, old.hsmp_nm, old.rn_adres, old.pnu, old.compet_de, old.hshld_co, old.suply_ty_nm, old.style_nm, old.suply_prvuse_ar, old.suply_cmnuse_ar, old.house_ty_nm, old.heat_mthd_detail_nm, old.buld_stle_nm, old.elvtr_instl_at_nm, old.parkng_co, old.bass_rent_gtn, old.bass_mt_rntchrg, old.bass_cnvrs_gtn_lmt
FROM legacy_complex_rows old JOIN myhome_complex_source_bundles bundle ON bundle.hsmp_sn = old.hsmp_sn
ORDER BY old.id;

CREATE TEMP TABLE legacy_announcement_rows ON COMMIT DROP AS
SELECT old.* FROM myhome_announcement_source old
WHERE NULLIF(BTRIM(old.pblanc_id), '') IS NOT NULL AND LENGTH(BTRIM(old.pblanc_id)) <= 100
    AND old.house_sn >= 0
    AND NOT EXISTS (SELECT 1 FROM myhome_announcement_source invalid
        WHERE BTRIM(invalid.pblanc_id) = BTRIM(old.pblanc_id)
            AND (invalid.house_sn IS NULL OR invalid.house_sn < 0))
    AND NOT EXISTS (SELECT 1 FROM myhome_announcement_source_bundles bundle
        WHERE bundle.pblanc_id = BTRIM(old.pblanc_id));
CREATE TEMP TABLE legacy_announcement_bundles ON COMMIT DROP AS
SELECT BTRIM(pblanc_id) pblanc_id, gen_random_uuid() record_id, COUNT(*) row_count
FROM legacy_announcement_rows GROUP BY BTRIM(pblanc_id);

INSERT INTO source_collection_records
    (id, version, source, started_at, finished_at, status, stored_row_count)
SELECT record_id, 0, 'MYHOME_ANNOUNCEMENT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'IMPORTED', row_count FROM legacy_announcement_bundles;
INSERT INTO source_collection_record_parameters(record_id, parameter_name, parameter_value)
SELECT record_id, 'LEGACY_TABLE', 'myhome_announcement_source' FROM legacy_announcement_bundles;

INSERT INTO myhome_announcement_source_bundles(version, pblanc_id, last_collection_record_id)
SELECT 0, pblanc_id, record_id FROM legacy_announcement_bundles;

INSERT INTO myhome_announcement_source_rows(source_id, collection_record_id, request_supply_type_code,
    collected_at, source_order, active, consecutive_miss_count, last_seen_run_id, pblanc_id, house_sn, sttus_nm, pblanc_nm, suply_instt_nm, house_ty_nm, suply_ty_nm, before_pblanc_id, rcrit_pblanc_de, przwner_presnatn_de, begin_de, end_de, refrnc, url, pc_url, mobile_url, hsmp_nm, brtc_nm, signgu_nm, full_adres, rn_code_nm, refrn_legaldong_nm, pnu, heat_mthd_nm, tot_hshld_co, sum_suply_co, rent_gtn, enty, surlus, mt_rntchrg)
SELECT bundle.id, bundle.last_collection_record_id, NULL, old.collected_at,
    ROW_NUMBER() OVER (PARTITION BY bundle.id ORDER BY old.id) - 1,
    old.active, old.consecutive_miss_count, old.last_seen_run_id, old.pblanc_id, old.house_sn, old.sttus_nm, old.pblanc_nm, old.suply_instt_nm, old.house_ty_nm, old.suply_ty_nm, old.before_pblanc_id, old.rcrit_pblanc_de, old.przwner_presnatn_de, old.begin_de, old.end_de, old.refrnc, old.url, old.pc_url, old.mobile_url, old.hsmp_nm, old.brtc_nm, old.signgu_nm, old.full_adres, old.rn_code_nm, old.refrn_legaldong_nm, old.pnu, old.heat_mthd_nm, old.tot_hshld_co, old.sum_suply_co, old.rent_gtn, old.enty, old.surlus, old.mt_rntchrg
FROM legacy_announcement_rows old JOIN myhome_announcement_source_bundles bundle
    ON bundle.pblanc_id = BTRIM(old.pblanc_id) ORDER BY old.id;

CREATE TEMP TABLE legacy_lease_bundle ON COMMIT DROP AS
SELECT gen_random_uuid() record_id, COUNT(*) row_count,
    CASE WHEN COUNT(collected_at) = COUNT(*) AND MIN(collected_at) = MAX(collected_at)
        THEN MAX(collected_at) END collected_at
FROM lh_catalog_source WHERE NOT EXISTS (SELECT 1 FROM lh_lease_catalog_source_bundles WHERE scope_key = 'ALL')
HAVING COUNT(*) > 0;

INSERT INTO source_collection_records
    (id, version, source, started_at, finished_at, status, stored_row_count)
SELECT record_id, 0, 'LH_LEASE_CATALOG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'IMPORTED', row_count FROM legacy_lease_bundle;
INSERT INTO source_collection_record_parameters(record_id, parameter_name, parameter_value)
SELECT record_id, 'LEGACY_TABLE', 'lh_catalog_source' FROM legacy_lease_bundle;

INSERT INTO lh_lease_catalog_source_bundles(version, scope_key, collected_at, last_collection_record_id)
SELECT 0, 'ALL', collected_at, record_id FROM legacy_lease_bundle;

INSERT INTO lh_lease_catalog_source_rows(source_id, source_order, collected_at, area_name, supply_type_name, complex_label, complex_total_unit_count, exclusive_area, total_unit_count, deposit_text, monthly_rent_text)
SELECT bundle.id, ROW_NUMBER() OVER (ORDER BY old.source_order NULLS LAST, old.id) - 1,
    old.collected_at, old.area_name, old.supply_type_name, old.complex_label, old.complex_total_unit_count, old.exclusive_area, old.total_unit_count, old.deposit_text, old.monthly_rent_text
FROM lh_catalog_source old CROSS JOIN legacy_lease_bundle imported
JOIN lh_lease_catalog_source_bundles bundle ON bundle.last_collection_record_id = imported.record_id
ORDER BY old.source_order NULLS LAST, old.id;

CREATE TEMP TABLE legacy_catalog_entries ON COMMIT DROP AS
SELECT old.*, gen_random_uuid() record_id, 1::bigint row_count FROM lh_announcement_catalog_source old
WHERE old.raw_payload IS JSON OBJECT
    AND NOT EXISTS (SELECT 1 FROM source_collection_records record
        WHERE record.source = 'LH_ANNOUNCEMENT_CATALOG' AND record.status = 'SUCCESS')
    AND NOT EXISTS (
    SELECT 1 FROM lh_announcement_catalog_entries current WHERE current.source_key = old.source_key);

INSERT INTO source_collection_records
    (id, version, source, started_at, finished_at, status, stored_row_count)
SELECT record_id, 0, 'LH_ANNOUNCEMENT_CATALOG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'IMPORTED', row_count FROM legacy_catalog_entries;
INSERT INTO source_collection_record_parameters(record_id, parameter_name, parameter_value)
SELECT record_id, 'LEGACY_TABLE', 'lh_announcement_catalog_source' FROM legacy_catalog_entries;

INSERT INTO lh_announcement_catalog_entries(version, source_key, pan_id, connection_system_division_code,
    upper_announcement_type_code, announcement_type_code, supply_info_type_code, announcement_name, status,
    notice_date, publication_date, closing_date, detail_url, mobile_detail_url, raw_payload,
    query_start_date, query_end_date, changed_at, collected_at, present_in_latest_catalog, last_collection_record_id)
SELECT 0, source_key, pan_id, connection_system_division_code, upper_announcement_type_code,
    announcement_type_code, supply_info_type_code, raw_payload::jsonb->>'PAN_NM', raw_payload::jsonb->>'PAN_SS',
    raw_payload::jsonb->>'PAN_NT_ST_DT', raw_payload::jsonb->>'PAN_DT', raw_payload::jsonb->>'CLSG_DT',
    raw_payload::jsonb->>'DTL_URL', raw_payload::jsonb->>'DTL_URL_MOB', raw_payload,
    NULL, NULL, changed_at, collected_at, present_in_latest_catalog, record_id FROM legacy_catalog_entries;

CREATE TEMP TABLE legacy_query_descriptions ON COMMIT DROP AS
SELECT source, pan_id, request_hash, MIN(request_description) request_description FROM (
    SELECT source, pan_id, request_hash, request_description FROM lh_announcement_collection_checkpoints
    UNION ALL SELECT source, pan_id, request_hash, request_description FROM lh_announcement_collection_links
) descriptions GROUP BY source, pan_id, request_hash HAVING COUNT(DISTINCT request_description) = 1;
CREATE TEMP TABLE legacy_query_candidates ON COMMIT DROP AS
SELECT descriptions.*, REGEXP_REPLACE(request_description, '&COLLECTION_VERSION=[0-9]+$', '') query_description,
    encode(sha256(convert_to(REGEXP_REPLACE(request_description, '&COLLECTION_VERSION=[0-9]+$', ''), 'UTF8')),
        'hex') query_hash
FROM legacy_query_descriptions descriptions
WHERE request_description ~ '^PAN_ID=[^&]+&CCR_CNNT_SYS_DS_CD=[^&]+&UPP_AIS_TP_CD=[^&]+&SPL_INF_TP_CD=[^&]+(&AIS_TP_CD=[^&]+)?(&COLLECTION_VERSION=[0-9]+)?$'
    AND split_part(split_part(request_description, '&', 1), '=', 2) = pan_id
    AND encode(sha256(convert_to(request_description, 'UTF8')), 'hex') = request_hash;
CREATE TEMP TABLE legacy_query_imports ON COMMIT DROP AS
SELECT candidate.*, gen_random_uuid() record_id, payload.row_count, payload.collected_at
FROM legacy_query_candidates candidate JOIN (
    SELECT 'LH_ANNOUNCEMENT_SUPPLY' source, pan_id, request_hash, COUNT(*) row_count,
        CASE WHEN COUNT(collected_at) = COUNT(*) AND MIN(collected_at) = MAX(collected_at)
            THEN MAX(collected_at) END collected_at FROM lh_announcement_supply_source GROUP BY pan_id, request_hash
    UNION ALL SELECT 'LH_ANNOUNCEMENT_DETAIL', pan_id, request_hash, COUNT(*),
        CASE WHEN COUNT(collected_at) = COUNT(*) AND MIN(collected_at) = MAX(collected_at)
            THEN MAX(collected_at) END FROM lh_announcement_detail_source GROUP BY pan_id, request_hash
) payload ON payload.source = candidate.source AND payload.pan_id = candidate.pan_id
    AND payload.request_hash = candidate.request_hash
WHERE NOT EXISTS (SELECT 1 FROM legacy_query_candidates other WHERE other.source = candidate.source
    AND other.query_hash = candidate.query_hash AND other.request_hash <> candidate.request_hash)
    AND NOT EXISTS (SELECT 1 FROM lh_announcement_query_sources current WHERE current.source = candidate.source
        AND current.query_hash = candidate.query_hash);
INSERT INTO source_collection_records(id, version, source, started_at, finished_at, status, stored_row_count)
SELECT record_id, 0, source, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'IMPORTED', row_count FROM legacy_query_imports;
INSERT INTO source_collection_record_parameters(record_id, parameter_name, parameter_value)
SELECT record_id, 'LEGACY_TABLE', CASE source WHEN 'LH_ANNOUNCEMENT_SUPPLY' THEN 'lh_announcement_supply_source'
    ELSE 'lh_announcement_detail_source' END FROM legacy_query_imports;
INSERT INTO lh_announcement_query_sources(version, source, pan_id, query_hash, request_hash,
    request_description, collected_at, verified_empty, last_collection_record_id)
SELECT 0, source, pan_id, query_hash, request_hash, request_description, collected_at, FALSE, record_id
FROM legacy_query_imports;
INSERT INTO lh_announcement_query_parameters(source_id, parameter_name, parameter_value)
SELECT current.id, split_part(parameter, '=', 1), split_part(parameter, '=', 2)
FROM legacy_query_imports imported JOIN lh_announcement_query_sources current
    ON current.last_collection_record_id = imported.record_id
CROSS JOIN LATERAL unnest(string_to_array(imported.request_description, '&')) parameter;
INSERT INTO source_collection_record_parameters(record_id, parameter_name, parameter_value)
SELECT imported.record_id, parameter.parameter_name, parameter.parameter_value FROM legacy_query_imports imported
JOIN lh_announcement_query_sources current ON current.last_collection_record_id = imported.record_id
JOIN lh_announcement_query_parameters parameter ON parameter.source_id = current.id;

INSERT INTO lh_announcement_supply_rows(source_id, source_order, collected_at, complex_label, type_name, exclusive_area, supply_area, total_unit_count, supplied_unit_count, deposit_text, monthly_rent_text)
SELECT current.id, ROW_NUMBER() OVER (PARTITION BY current.id ORDER BY old.source_order NULLS LAST, old.id) - 1,
    old.collected_at, old.complex_label, old.type_name, old.exclusive_area, old.supply_area, old.total_unit_count, old.supplied_unit_count, old.deposit_text, old.monthly_rent_text
FROM lh_announcement_supply_source old JOIN legacy_query_imports imported
    ON imported.source = 'LH_ANNOUNCEMENT_SUPPLY' AND imported.pan_id = old.pan_id
    AND imported.request_hash = old.request_hash JOIN lh_announcement_query_sources current
    ON current.last_collection_record_id = imported.record_id ORDER BY old.id;

INSERT INTO lh_announcement_detail_rows(source_id, source_order, collected_at, dataset_type, complex_name, address, detail_address, total_unit_count, heating_description, exclusive_area_range, expected_move_in_year_month, guidance_text, application_period, application_begin_date, application_end_date, winner_announcement_date, document_target_announcement_date, document_submission_begin_date, document_submission_end_date, contract_begin_date, contract_end_date, reception_address, reception_detail_address, operation_begin, operation_end, phone, reception_guidance, kind, name, url, attachment_complex_name, correction_reason, etc_contents)
SELECT current.id, ROW_NUMBER() OVER (PARTITION BY current.id ORDER BY old.source_order NULLS LAST, old.id) - 1,
    old.collected_at, old.dataset_type, old.complex_name, old.address, old.detail_address, old.total_unit_count, old.heating_description, old.exclusive_area_range, old.expected_move_in_year_month, old.guidance_text, old.application_period, old.application_begin_date, old.application_end_date, old.winner_announcement_date, old.document_target_announcement_date, old.document_submission_begin_date, old.document_submission_end_date, old.contract_begin_date, old.contract_end_date, old.reception_address, old.reception_detail_address, old.operation_begin, old.operation_end, old.phone, old.reception_guidance, old.kind, old.name, old.url, old.attachment_complex_name, old.correction_reason, old.etc_contents
FROM lh_announcement_detail_source old JOIN legacy_query_imports imported
    ON imported.source = 'LH_ANNOUNCEMENT_DETAIL' AND imported.pan_id = old.pan_id
    AND imported.request_hash = old.request_hash JOIN lh_announcement_query_sources current
    ON current.last_collection_record_id = imported.record_id ORDER BY old.id;

INSERT INTO source_legacy_import_report(source, legacy_row_count, imported_row_count,
    preserved_legacy_row_count, imported_at)
SELECT 'MYHOME_COMPLEX', (SELECT COUNT(*) FROM myhome_complex_source), COALESCE(SUM(record.stored_row_count), 0),
    (SELECT COUNT(*) FROM myhome_complex_source) - COALESCE(SUM(record.stored_row_count), 0), CURRENT_TIMESTAMP
FROM source_collection_records record JOIN source_collection_record_parameters parameter
    ON parameter.record_id = record.id AND parameter.parameter_name = 'LEGACY_TABLE'
    AND parameter.parameter_value = 'myhome_complex_source' WHERE record.status = 'IMPORTED';

INSERT INTO source_legacy_import_report(source, legacy_row_count, imported_row_count,
    preserved_legacy_row_count, imported_at)
SELECT 'MYHOME_ANNOUNCEMENT', (SELECT COUNT(*) FROM myhome_announcement_source), COALESCE(SUM(record.stored_row_count), 0),
    (SELECT COUNT(*) FROM myhome_announcement_source) - COALESCE(SUM(record.stored_row_count), 0), CURRENT_TIMESTAMP
FROM source_collection_records record JOIN source_collection_record_parameters parameter
    ON parameter.record_id = record.id AND parameter.parameter_name = 'LEGACY_TABLE'
    AND parameter.parameter_value = 'myhome_announcement_source' WHERE record.status = 'IMPORTED';

INSERT INTO source_legacy_import_report(source, legacy_row_count, imported_row_count,
    preserved_legacy_row_count, imported_at)
SELECT 'LH_LEASE_CATALOG', (SELECT COUNT(*) FROM lh_catalog_source), COALESCE(SUM(record.stored_row_count), 0),
    (SELECT COUNT(*) FROM lh_catalog_source) - COALESCE(SUM(record.stored_row_count), 0), CURRENT_TIMESTAMP
FROM source_collection_records record JOIN source_collection_record_parameters parameter
    ON parameter.record_id = record.id AND parameter.parameter_name = 'LEGACY_TABLE'
    AND parameter.parameter_value = 'lh_catalog_source' WHERE record.status = 'IMPORTED';

INSERT INTO source_legacy_import_report(source, legacy_row_count, imported_row_count,
    preserved_legacy_row_count, imported_at)
SELECT 'LH_ANNOUNCEMENT_CATALOG', (SELECT COUNT(*) FROM lh_announcement_catalog_source), COALESCE(SUM(record.stored_row_count), 0),
    (SELECT COUNT(*) FROM lh_announcement_catalog_source) - COALESCE(SUM(record.stored_row_count), 0), CURRENT_TIMESTAMP
FROM source_collection_records record JOIN source_collection_record_parameters parameter
    ON parameter.record_id = record.id AND parameter.parameter_name = 'LEGACY_TABLE'
    AND parameter.parameter_value = 'lh_announcement_catalog_source' WHERE record.status = 'IMPORTED';

INSERT INTO source_legacy_import_report(source, legacy_row_count, imported_row_count,
    preserved_legacy_row_count, imported_at)
SELECT 'LH_ANNOUNCEMENT_SUPPLY', (SELECT COUNT(*) FROM lh_announcement_supply_source), COALESCE(SUM(record.stored_row_count), 0),
    (SELECT COUNT(*) FROM lh_announcement_supply_source) - COALESCE(SUM(record.stored_row_count), 0), CURRENT_TIMESTAMP
FROM source_collection_records record JOIN source_collection_record_parameters parameter
    ON parameter.record_id = record.id AND parameter.parameter_name = 'LEGACY_TABLE'
    AND parameter.parameter_value = 'lh_announcement_supply_source' WHERE record.status = 'IMPORTED';

INSERT INTO source_legacy_import_report(source, legacy_row_count, imported_row_count,
    preserved_legacy_row_count, imported_at)
SELECT 'LH_ANNOUNCEMENT_DETAIL', (SELECT COUNT(*) FROM lh_announcement_detail_source), COALESCE(SUM(record.stored_row_count), 0),
    (SELECT COUNT(*) FROM lh_announcement_detail_source) - COALESCE(SUM(record.stored_row_count), 0), CURRENT_TIMESTAMP
FROM source_collection_records record JOIN source_collection_record_parameters parameter
    ON parameter.record_id = record.id AND parameter.parameter_name = 'LEGACY_TABLE'
    AND parameter.parameter_value = 'lh_announcement_detail_source' WHERE record.status = 'IMPORTED';

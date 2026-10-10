-- Compatibility read-model schema for collection storage slices.
-- Columns follow B20260922_01 and the source request-hash/catalog lifecycle migrations.
-- Always executed on the test connection's isolated schema; no public schema writes.

CREATE TABLE lh_announcement_collection_checkpoints (
    id bigserial PRIMARY KEY,
    completed_at timestamp(6) with time zone NOT NULL,
    pan_id character varying(100) NOT NULL,
    request_description character varying(2000) NOT NULL,
    request_hash character varying(64) NOT NULL,
    source character varying(40) NOT NULL,
    source_announcement_key character varying(500) NOT NULL,
    CONSTRAINT lh_announcement_collection_checkpoints_source_check CHECK (((source)::text = ANY ((ARRAY['MYHOME_COMPLEX'::character varying, 'MYHOME_ANNOUNCEMENT'::character varying, 'LH_LEASE_CATALOG'::character varying, 'LH_ANNOUNCEMENT_DETAIL'::character varying, 'LH_ANNOUNCEMENT_SUPPLY'::character varying])::text[])))
);

CREATE TABLE lh_announcement_collection_links (
    id bigserial PRIMARY KEY,
    completed_at timestamp(6) with time zone NOT NULL,
    pan_id character varying(100) NOT NULL,
    request_description character varying(2000) NOT NULL,
    request_hash character varying(64) NOT NULL,
    source character varying(40) NOT NULL,
    source_announcement_key character varying(500) NOT NULL,
    CONSTRAINT lh_announcement_collection_links_source_check CHECK (((source)::text = ANY ((ARRAY['MYHOME_COMPLEX'::character varying, 'MYHOME_ANNOUNCEMENT'::character varying, 'LH_LEASE_CATALOG'::character varying, 'LH_ANNOUNCEMENT_DETAIL'::character varying, 'LH_ANNOUNCEMENT_SUPPLY'::character varying])::text[])))
);

CREATE TABLE lh_announcement_detail_source (
    id bigserial PRIMARY KEY,
    address character varying(255),
    application_period character varying(255),
    attachment_complex_name character varying(255),
    collected_at timestamp(6) with time zone,
    complex_name character varying(255),
    contract_begin_date character varying(255),
    contract_end_date character varying(255),
    correction_reason character varying(4000),
    dataset_type character varying(255),
    detail_address character varying(255),
    document_submission_begin_date character varying(255),
    document_submission_end_date character varying(255),
    document_target_announcement_date character varying(255),
    etc_contents character varying(4000),
    exclusive_area_range character varying(255),
    expected_move_in_year_month character varying(255),
    guidance_text character varying(4000),
    heating_description character varying(255),
    kind character varying(255),
    name character varying(255),
    operation_begin character varying(255),
    operation_end character varying(255),
    pan_id character varying(255),
    phone character varying(255),
    reception_address character varying(255),
    reception_detail_address character varying(255),
    reception_guidance character varying(4000),
    source_order integer,
    total_unit_count character varying(255),
    url character varying(1000),
    request_hash varchar(64)
);

CREATE TABLE lh_announcement_supply_source (
    id bigserial PRIMARY KEY,
    collected_at timestamp(6) with time zone,
    complex_label character varying(255),
    deposit_text character varying(255),
    exclusive_area character varying(255),
    monthly_rent_text character varying(255),
    pan_id character varying(255),
    source_order integer,
    supplied_unit_count character varying(255),
    supply_area character varying(255),
    total_unit_count character varying(255),
    type_name character varying(255),
    request_hash varchar(64)
);

CREATE TABLE lh_catalog_source (
    id bigserial PRIMARY KEY,
    area_name character varying(255),
    collected_at timestamp(6) with time zone,
    complex_label character varying(255),
    complex_total_unit_count character varying(255),
    deposit_text character varying(255),
    exclusive_area character varying(255),
    monthly_rent_text character varying(255),
    source_order integer,
    supply_type_name character varying(255),
    total_unit_count character varying(255)
);

CREATE TABLE myhome_announcement_source (
    id bigserial PRIMARY KEY,
    before_pblanc_id character varying(255),
    begin_de character varying(255),
    brtc_nm character varying(255),
    collected_at timestamp(6) with time zone,
    end_de character varying(255),
    enty bigint,
    full_adres character varying(1000),
    heat_mthd_nm character varying(255),
    house_sn integer,
    house_ty_nm character varying(255),
    hsmp_nm character varying(255),
    mobile_url character varying(2000),
    mt_rntchrg bigint,
    pblanc_id character varying(255),
    pblanc_nm character varying(255),
    pc_url character varying(2000),
    pnu character varying(255),
    przwner_presnatn_de character varying(255),
    rcrit_pblanc_de character varying(255),
    refrn_legaldong_nm character varying(255),
    refrnc character varying(2000),
    rent_gtn bigint,
    rn_code_nm character varying(255),
    signgu_nm character varying(255),
    source_key character varying(500) NOT NULL,
    source_order integer,
    sttus_nm character varying(255),
    sum_suply_co integer,
    suply_instt_nm character varying(255),
    suply_ty_nm character varying(255),
    surlus bigint,
    tot_hshld_co character varying(255),
    url character varying(2000),
    active boolean NOT NULL,
    consecutive_miss_count integer NOT NULL,
    last_seen_run_id character varying(100)
);

CREATE TABLE myhome_complex_source (
    id bigserial PRIMARY KEY,
    bass_cnvrs_gtn_lmt bigint,
    bass_mt_rntchrg bigint,
    bass_rent_gtn bigint,
    brtc_code character varying(255),
    brtc_nm character varying(255),
    buld_stle_nm character varying(255),
    collected_at timestamp(6) with time zone,
    compet_de character varying(255),
    elvtr_instl_at_nm character varying(255),
    heat_mthd_detail_nm character varying(255),
    house_ty_nm character varying(255),
    hshld_co integer,
    hsmp_nm character varying(255),
    hsmp_sn bigint,
    instt_nm character varying(255),
    parkng_co integer,
    pnu character varying(255),
    rn_adres character varying(255),
    signgu_code character varying(255),
    signgu_nm character varying(255),
    source_key character varying(500) NOT NULL,
    style_nm character varying(255),
    suply_cmnuse_ar numeric(10,4),
    suply_prvuse_ar numeric(10,4),
    suply_ty_nm character varying(255)
);

CREATE TABLE lh_announcement_catalog_source (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    source_key varchar(200) NOT NULL UNIQUE,
    pan_id varchar(100) NOT NULL,
    connection_system_division_code varchar(10) NOT NULL,
    upper_announcement_type_code varchar(10) NOT NULL,
    announcement_type_code varchar(10) NOT NULL,
    supply_info_type_code varchar(10) NOT NULL,
    content_fingerprint varchar(64) NOT NULL,
    raw_payload text NOT NULL,
    changed_at timestamp with time zone NOT NULL,
    collected_at timestamp with time zone NOT NULL
);

ALTER TABLE lh_announcement_catalog_source
    ADD COLUMN present_in_latest_catalog boolean NOT NULL DEFAULT true;


ALTER TABLE lh_announcement_detail_source
    ADD COLUMN application_begin_date varchar(255),
    ADD COLUMN application_end_date varchar(255),
    ADD COLUMN winner_announcement_date varchar(255);

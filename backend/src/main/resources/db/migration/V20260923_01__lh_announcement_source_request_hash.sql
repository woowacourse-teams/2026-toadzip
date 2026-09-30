ALTER TABLE public.lh_announcement_detail_source
    ADD COLUMN request_hash varchar(64);

ALTER TABLE public.lh_announcement_supply_source
    ADD COLUMN request_hash varchar(64);

ALTER TABLE public.lh_announcement_detail_source
    DROP CONSTRAINT ukhvlfwy2ho50pxnjv8kft91qxh;

ALTER TABLE public.lh_announcement_supply_source
    DROP CONSTRAINT ukmf6m5jqtm9imthkguo0fjqnte;

ALTER TABLE public.lh_announcement_detail_source
    ADD CONSTRAINT uk_lh_detail_source_request_row
        UNIQUE (pan_id, request_hash, source_order, dataset_type);

ALTER TABLE public.lh_announcement_supply_source
    ADD CONSTRAINT uk_lh_supply_source_request_row
        UNIQUE (pan_id, request_hash, source_order);

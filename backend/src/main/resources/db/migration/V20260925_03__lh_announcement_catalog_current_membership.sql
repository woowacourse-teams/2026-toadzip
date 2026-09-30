ALTER TABLE public.lh_announcement_catalog_source
    ADD COLUMN present_in_latest_catalog boolean NOT NULL DEFAULT true;

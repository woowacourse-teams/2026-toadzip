ALTER TABLE public.lh_announcement_detail_source
    ADD COLUMN application_begin_date varchar(255),
    ADD COLUMN application_end_date varchar(255),
    ADD COLUMN winner_announcement_date varchar(255);

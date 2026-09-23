#!/bin/sh
set -eu

schema_state=$(psql -X -At -v ON_ERROR_STOP=1 -c "
    SELECT CASE
        WHEN to_regclass('public.announcements') IS NULL
         AND to_regclass('public.supply_rows') IS NULL THEN 'new'
        WHEN to_regclass('public.announcements') IS NOT NULL
         AND to_regclass('public.supply_rows') IS NOT NULL THEN 'existing'
        ELSE 'partial'
    END
")

if [ "$schema_state" = new ]; then
    echo 'New local database: Hibernate will create the ownership columns.'
    exit 0
fi

if [ "$schema_state" != existing ]; then
    echo 'Local database has an incomplete announcement schema.' >&2
    exit 1
fi

missing_columns=$(psql -X -At -v ON_ERROR_STOP=1 -c "
    SELECT 3 - count(*)
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND (
          (table_name = 'announcements' AND column_name = 'lh_reception_place_owned')
          OR (table_name = 'supply_rows' AND column_name IN (
              'lh_total_supply_household_count_owned',
              'lh_total_supply_household_count_enriched'
          ))
      )
")

if [ "$missing_columns" = 0 ]; then
    echo 'Local LH ownership columns are already present.'
    exit 0
fi

if [ "$(psql -X -At -v ON_ERROR_STOP=1 -c "SELECT to_regclass('public.lh_announcement_supply_source')")" = '' ]; then
    echo 'Local database is missing lh_announcement_supply_source.' >&2
    exit 1
fi

psql -X -v ON_ERROR_STOP=1 -f /migrations/V20260918_01__add_lh_field_ownership.sql
psql -X -v ON_ERROR_STOP=1 -f /migrations/V20260918_02__correct_lh_household_count_ownership.sql
echo 'Local LH ownership migrations completed.'

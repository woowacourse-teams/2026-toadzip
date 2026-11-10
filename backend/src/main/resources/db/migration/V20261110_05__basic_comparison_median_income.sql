-- D14 다산지금A3 PDF p8: 2026년 기준 중위소득 100%.
-- Use the natural key; preserve existing reference rows if an operator already entered them.
INSERT INTO income_standard_tables
    (standard_kind, base_year, source_note, extra_per_person_krw, max_listed_household_size)
VALUES ('STANDARD_MEDIAN_INCOME', 2026, 'D14 다산지금A3 PDF p8, 2026년 기준 중위소득 100%', 959198, 8)
ON CONFLICT (standard_kind, base_year) DO NOTHING;

INSERT INTO income_standard_values (table_id, household_size, amount_100_krw)
SELECT standard.id, amounts.household_size, amounts.amount
FROM income_standard_tables standard
CROSS JOIN (VALUES (1, 2564238), (2, 4199292), (3, 5359036), (4, 6494738),
    (5, 7556719), (6, 8555952), (7, 9515150), (8, 10474348)) AS amounts(household_size, amount)
WHERE standard.standard_kind = 'STANDARD_MEDIAN_INCOME' AND standard.base_year = 2026
ON CONFLICT (table_id, household_size) DO NOTHING;

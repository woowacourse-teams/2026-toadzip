-- 2025년 도시근로자 가구원수별 월평균소득 100% 기준값.
INSERT INTO income_standard_tables (id, standard_kind, base_year, source_note, extra_per_person_krw, max_listed_household_size) VALUES (1, 'URBAN_WORKER_MONTHLY_INCOME', 2025, 'D05 사천벌리1 p5, D01 동탄호수공원 p19의 2025년 도시근로자 가구원수별 월평균소득 100%', 579278, 8);
INSERT INTO income_standard_values (table_id, household_size, amount_100_krw) VALUES (1, 1, 3813363);
INSERT INTO income_standard_values (table_id, household_size, amount_100_krw) VALUES (1, 2, 5866270);
INSERT INTO income_standard_values (table_id, household_size, amount_100_krw) VALUES (1, 3, 8168429);
INSERT INTO income_standard_values (table_id, household_size, amount_100_krw) VALUES (1, 4, 8802202);
INSERT INTO income_standard_values (table_id, household_size, amount_100_krw) VALUES (1, 5, 9326985);
INSERT INTO income_standard_values (table_id, household_size, amount_100_krw) VALUES (1, 6, 9906263);
INSERT INTO income_standard_values (table_id, household_size, amount_100_krw) VALUES (1, 7, 10485541);
INSERT INTO income_standard_values (table_id, household_size, amount_100_krw) VALUES (1, 8, 11064819);
SELECT setval(pg_get_serial_sequence('income_standard_tables', 'id'), 1);

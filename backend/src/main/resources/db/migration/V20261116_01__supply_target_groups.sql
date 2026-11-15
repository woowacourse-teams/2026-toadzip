-- 공고 공급대상 분류(SupplyTargetGroup, 여러 개). 분류를 정할 수 없는 표기(전체, 청년 창업인)는 빈 목록이다.
ALTER TABLE announcement_supply_targets
    ADD COLUMN target_groups JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE announcement_supply_targets
    ADD CONSTRAINT ck_announcement_supply_targets_groups CHECK (
        jsonb_typeof(target_groups) = 'array'
        AND target_groups <@ '["GENERAL", "YOUTH", "UNIVERSITY_STUDENT", "NEWLYWED", "PRE_NEWLYWED", "SINGLE_PARENT",
            "ELDERLY", "HOUSING_BENEFIT_RECIPIENT", "GA_CLASS", "NA_CLASS", "EXISTING_RESIDENT", "INDUSTRIAL_WORKER",
            "HOUSING_VULNERABLE"]'::jsonb);

-- 저장된 공급대상 표기를 분류로 채운다. 규칙은 AI 워커 target_groups.groups와 같고 이행에서 한 번만 쓴다.
-- 낱말 사이 띄어쓰기를 붙이고, 주거약자 표기는 구분으로 읽은 뒤 괄호 설명과 함께 지우고, 구분 기호로 낱말을 나눈다.
-- 모르는 낱말이 하나라도 있으면 분류를 정하지 않는다(부분 글자로 맞추면 '청년 창업인'이 청년이 된다).
WITH joined AS (
    SELECT id,
           regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(
               label,
               '「[[:space:]]*([가나])[[:space:]]*」[[:space:]]*군', '\1군', 'g'),
               '생계[[:space:]]*[·ㆍ・,]?[[:space:]]*의료급여[[:space:]]*수급자', '생계의료급여수급자', 'g'),
               '주거급여[[:space:]]*수급자', '주거급여수급자', 'g'),
               '예비[[:space:]]+신혼부부', '예비신혼부부', 'g'),
               '한부모[[:space:]]+가족', '한부모가족', 'g'),
               '일반[[:space:]]+공급', '일반공급', 'g'),
               '산업단지[[:space:]]*근로자', '산업단지근로자', 'g') AS text
    FROM announcement_supply_targets
),
stripped AS (
    SELECT id,
           strpos(text, '주거약자') > 0 AS vulnerable,
           regexp_replace(regexp_replace(text, '\([^)]*\)|\[[^]]*\]', ' ', 'g'), '주거약자용?', ' ', 'g') AS text
    FROM joined
),
tokens AS (
    SELECT s.id, token
    FROM stripped s
    CROSS JOIN LATERAL regexp_split_to_table(s.text, '[[:space:]·ㆍ・․,/_]+') AS token
    WHERE token <> '' AND token NOT IN ('등', '소득', '계층')
),
names (token, target_group) AS (
    VALUES ('일반', 'GENERAL'), ('일반공급', 'GENERAL'), ('일반공급대상', 'GENERAL'),
           ('청년', 'YOUTH'),
           ('대학생', 'UNIVERSITY_STUDENT'), ('취업준비생', 'UNIVERSITY_STUDENT'),
           ('신혼부부', 'NEWLYWED'), ('신혼', 'NEWLYWED'),
           ('예비신혼부부', 'PRE_NEWLYWED'),
           ('한부모', 'SINGLE_PARENT'), ('한부모가족', 'SINGLE_PARENT'),
           ('고령자', 'ELDERLY'),
           ('주거급여수급자', 'HOUSING_BENEFIT_RECIPIENT'),
           ('가군', 'GA_CLASS'), ('생계의료급여수급자', 'GA_CLASS'),
           ('나군', 'NA_CLASS'),
           ('기존거주자', 'EXISTING_RESIDENT'),
           ('산업단지근로자', 'INDUSTRIAL_WORKER'), ('산단근로자', 'INDUSTRIAL_WORKER')
),
group_order (target_group, position) AS (
    VALUES ('GENERAL', 1), ('YOUTH', 2), ('UNIVERSITY_STUDENT', 3), ('NEWLYWED', 4), ('PRE_NEWLYWED', 5),
           ('SINGLE_PARENT', 6), ('ELDERLY', 7), ('HOUSING_BENEFIT_RECIPIENT', 8), ('GA_CLASS', 9), ('NA_CLASS', 10),
           ('EXISTING_RESIDENT', 11), ('INDUSTRIAL_WORKER', 12), ('HOUSING_VULNERABLE', 13)
),
unknown AS (
    SELECT DISTINCT t.id
    FROM tokens t
    LEFT JOIN names n ON n.token = t.token
    WHERE n.token IS NULL
),
found AS (
    SELECT t.id, n.target_group FROM tokens t JOIN names n ON n.token = t.token
    UNION
    SELECT id, 'HOUSING_VULNERABLE' FROM stripped WHERE vulnerable
),
grouped AS (
    SELECT f.id, jsonb_agg(f.target_group ORDER BY o.position) AS target_groups
    FROM found f
    JOIN group_order o ON o.target_group = f.target_group
    WHERE f.id NOT IN (SELECT id FROM unknown)
    GROUP BY f.id
)
UPDATE announcement_supply_targets st
SET target_groups = g.target_groups
FROM grouped g
WHERE st.id = g.id;

-- LH 공고목록 원천은 건설임대 공고(상위유형 06의 공급유형 060~063, 신혼희망타운 임대 42·51)만 둔다.
-- 수집이 그 밖의 행을 더 저장하지 않으므로 이전에 쌓인 토지·상가·분양·매입임대 행을 지운다. 이 표를 참조하는 FK는 없다.
DELETE FROM lh_announcement_catalog_entries
WHERE NOT (
    (btrim(coalesce(upper_announcement_type_code, '')) = '06'
        AND btrim(coalesce(supply_info_type_code, '')) IN ('060', '061', '062', '063'))
    OR (btrim(coalesce(upper_announcement_type_code, '')) = '39'
        AND btrim(coalesce(announcement_type_code, '')) IN ('42', '51'))
);

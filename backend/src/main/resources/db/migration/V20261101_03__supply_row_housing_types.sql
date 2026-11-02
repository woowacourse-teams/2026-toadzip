-- 공급행이 함께 모집하는 주택형. LH 공급 한 행은 여러 전용면적을 묶어 모집하므로 공급행 하나가 주택형 여럿을 가리킨다.
-- 대표 주택형(supply_rows.housing_type_id)도 이 표에 함께 둔다. 지금은 대표 하나만 연결한다.
-- 주택형 삭제는 연결까지 확인한 뒤에만 하므로, 연결을 모르는 이전 애플리케이션이 지우는 주택형의 낡은 연결만 함께 지운다.
CREATE TABLE supply_row_housing_types (
    supply_row_id BIGINT NOT NULL REFERENCES supply_rows(id) ON DELETE CASCADE,
    housing_type_id BIGINT NOT NULL REFERENCES housing_types(id) ON DELETE CASCADE,
    PRIMARY KEY (supply_row_id, housing_type_id)
);

CREATE INDEX idx_supply_row_housing_types_type ON supply_row_housing_types (housing_type_id);

INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id)
SELECT id, housing_type_id FROM supply_rows WHERE housing_type_id IS NOT NULL;

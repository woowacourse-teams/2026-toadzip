BEGIN;

CREATE TABLE IF NOT EXISTS road_address_locations (
    road_name_code VARCHAR(12) NOT NULL,
    underground VARCHAR(1) NOT NULL,
    building_main_number INTEGER NOT NULL,
    building_sub_number INTEGER NOT NULL,
    entrance_serial VARCHAR(10) NOT NULL,
    province_code VARCHAR(2) NOT NULL,
    road_address VARCHAR(500) NOT NULL,
    normalized_road_address VARCHAR(500) NOT NULL,
    x NUMERIC(15, 6),
    y NUMERIC(15, 6),
    PRIMARY KEY (
        road_name_code,
        underground,
        building_main_number,
        building_sub_number,
        entrance_serial
    ),
    CONSTRAINT ck_road_address_location_coordinate_pair
        CHECK ((x IS NULL AND y IS NULL) OR (x IS NOT NULL AND y IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_road_address_location_address
    ON road_address_locations (normalized_road_address);

COMMIT;

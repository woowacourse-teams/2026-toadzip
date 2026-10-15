-- 지도 영역에 포함된 단지의 주택형 조회와 집계를 지원한다.
-- 기존 앱과 호환되는 추가 인덱스이므로 앱 롤백 시에도 유지할 수 있다.
CREATE INDEX idx_housing_types_housing_complex_id ON housing_types (housing_complex_id);

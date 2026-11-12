-- 카카오·구글 계정의 관리자 여부. 관리자 화면에서 주고 빼며, 주고 뺀 기록은 admin_data_changes에 남긴다.
ALTER TABLE users ADD COLUMN admin BOOLEAN NOT NULL DEFAULT FALSE;

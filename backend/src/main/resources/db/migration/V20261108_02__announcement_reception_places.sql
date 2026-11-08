-- 공고 접수처를 여러 곳으로 나눈다. 공고문에는 인터넷 접수와 현장 접수처 여러 곳이 함께 적히는데,
-- 공고 표의 접수처 칸은 한 곳만 담았다. 별도 표로 옮기고 지금 값은 첫 접수처로 옮긴 뒤 옛 칸을 지운다.
CREATE TABLE announcement_reception_places (
    announcement_id BIGINT NOT NULL REFERENCES announcements (id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    method VARCHAR(255) NOT NULL,
    address VARCHAR(255),
    contact VARCHAR(255),
    url VARCHAR(255),
    guidance VARCHAR(1000),
    PRIMARY KEY (announcement_id, display_order),
    CONSTRAINT announcement_reception_places_method_check CHECK (method IN (
        'ONLINE', '인터넷', 'VISIT', '현장', 'MAIL', '우편', 'ETC', '기타'
    ))
);

-- 이름·방식이 모두 있는 행만 접수처다. 한쪽만 있거나 이름 없이 주소·연락처·URL만 남은 행은 옮길 수 없어,
-- 옛 칸을 지우기 전에 멈춰 사람이 값을 정하게 한다(조용히 버리면 되살릴 수 없다).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM announcements
               WHERE (reception_place_name IS NULL) <> (reception_method IS NULL)
                  OR (reception_place_name IS NULL AND COALESCE(reception_address, reception_contact, reception_url) IS NOT NULL)) THEN
        RAISE EXCEPTION '접수처 이름·방식 중 하나만 있거나 이름 없이 다른 값만 있는 공고가 있어 접수처를 옮길 수 없습니다.';
    END IF;
END $$;

INSERT INTO announcement_reception_places (announcement_id, display_order, name, method, address, contact, url)
SELECT id, 0, reception_place_name, reception_method, reception_address, reception_contact, reception_url
FROM announcements
WHERE reception_place_name IS NOT NULL AND reception_method IS NOT NULL;

ALTER TABLE announcements
    DROP CONSTRAINT IF EXISTS announcements_reception_method_check,
    DROP COLUMN reception_place_name,
    DROP COLUMN reception_method,
    DROP COLUMN reception_address,
    DROP COLUMN reception_contact,
    DROP COLUMN reception_url;

\set ON_ERROR_STOP on

-- 격리된 복원 DB에서만 실행한다. 서비스 연결 전에 취소 이후 백업된 구독이 되살아나지 않게 한다.
BEGIN;
DO $$
BEGIN
    IF current_database() !~ '^toadzip_restore(_[a-z0-9]+)?$' THEN
        RAISE EXCEPTION 'Refusing notification reset outside an isolated toadzip_restore database';
    END IF;
END
$$;

DELETE FROM notification_subscriptions;
DELETE FROM notification_email_preferences;
COMMIT;

SELECT
    (SELECT count(*) FROM notification_subscriptions) AS member_subscriptions,
    (SELECT count(*) FROM notification_email_preferences) AS member_emails;

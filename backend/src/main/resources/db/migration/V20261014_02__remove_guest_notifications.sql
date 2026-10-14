-- Guest notification registration was temporary. Member settings and privacy metadata remain.
-- Remove dependent tables first; do not cascade into unrelated data.
DROP TABLE notification_guest_cancellation_requests;
DROP TABLE notification_guest_subscriptions;
DROP TABLE notification_guest_email_preferences;

import importlib.util
from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest
from unittest.mock import Mock

REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("backup_monitor", REPO / "infra/db/automation/handler.py")
MONITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITOR)
NOW = datetime(2026, 9, 30, 3, 0, tzinfo=timezone.utc)


def object_at(hours, size=10):
    return {"Key": "backups/db-prod/test.dump", "LastModified": NOW - timedelta(hours=hours), "Size": size}


def s3_mock():
    client = Mock()
    client.get_paginator.return_value.paginate.return_value = [{"Contents": [object_at(2)]}]
    client.get_bucket_versioning.return_value = {}
    client.get_bucket_lifecycle_configuration.return_value = {"Rules": [{
        "ID": "toadzip-db-backup-30d", "Status": "Enabled", "Filter": {"Prefix": "backups/"},
        "Expiration": {"Days": 30}
    }]}
    client.get_public_access_block.return_value = {"PublicAccessBlockConfiguration": {
        key: True for key in ("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets")}}
    client.get_bucket_encryption.return_value = {"ServerSideEncryptionConfiguration": {"Rules": [{
        "ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]}}
    return client


class BackupMonitorTest(unittest.TestCase):
    def test_reports_each_database_and_bucket_policy_without_dump_access(self):
        s3, cloudwatch = s3_mock(), Mock()
        health = MONITOR.check_health(s3, cloudwatch, "backup-bucket", "123456789012", NOW)
        self.assertEqual({"db-prod": 1, "db-dev": 1, "db-shared": 1, "bucket-policy": 1}, health)
        calls = s3.get_paginator.return_value.paginate.call_args_list
        self.assertEqual(["backups/db-prod/", "backups/db-dev/", "backups/db-shared/"],
                         [call.kwargs["Prefix"] for call in calls])
        s3.get_object.assert_not_called()
        self.assertEqual(4, len(cloudwatch.put_metric_data.call_args.kwargs["MetricData"]))

    def test_missing_stale_and_empty_latest_backups_are_unhealthy(self):
        for objects in ([], [object_at(27)], [object_at(24), object_at(1, size=0)]):
            with self.subTest(objects=objects):
                s3 = s3_mock()
                s3.get_paginator.return_value.paginate.return_value = [{"Contents": objects}]
                self.assertFalse(MONITOR.backup_is_fresh(s3, "bucket", "123456789012", "db-prod", NOW))

    def test_reads_all_pages_and_uses_latest_backup(self):
        s3 = s3_mock()
        s3.get_paginator.return_value.paginate.return_value = [
            {"Contents": [object_at(40)]}, {"Contents": [object_at(1)]}]
        self.assertTrue(MONITOR.backup_is_fresh(s3, "bucket", "123456789012", "db-prod", NOW))

    def test_wrong_retention_or_versioning_is_unhealthy(self):
        s3 = s3_mock()
        s3.get_bucket_versioning.return_value = {"Status": "Suspended"}
        self.assertFalse(MONITOR.policy_is_valid(s3, "bucket", "123456789012"))
        s3.get_bucket_versioning.return_value = {}
        s3.get_bucket_lifecycle_configuration.return_value["Rules"][0]["Expiration"]["Days"] = 31
        self.assertFalse(MONITOR.policy_is_valid(s3, "bucket", "123456789012"))

    def test_api_failure_publishes_unhealthy_metrics_instead_of_success(self):
        s3, cloudwatch = s3_mock(), Mock()
        s3.get_paginator.side_effect = RuntimeError("unavailable")
        health = MONITOR.check_health(s3, cloudwatch, "bucket", "123456789012", NOW)
        self.assertEqual(0, health["db-prod"])
        self.assertEqual(0, health["db-dev"])
        self.assertEqual(0, health["db-shared"])
        self.assertEqual(1, health["bucket-policy"])
        cloudwatch.put_metric_data.assert_called_once()


if __name__ == "__main__":
    unittest.main()

"""Run in AWS independently of the DB host; publish health only, never database contents."""
from datetime import datetime, timezone
import logging
import os

SERVICES = ("db-prod", "db-dev", "db-shared")
MAX_AGE_SECONDS = 26 * 60 * 60
LOGGER = logging.getLogger(__name__)


def backup_is_fresh(s3, bucket, account, service, now):
    objects = []
    for page in s3.get_paginator("list_objects_v2").paginate(
        Bucket=bucket, Prefix=f"backups/{service}/", ExpectedBucketOwner=account
    ):
        objects.extend(item for item in page.get("Contents", []) if item["Key"].endswith(".dump"))
    if not objects:
        return False
    latest = max(objects, key=lambda item: item["LastModified"])
    age = (now - latest["LastModified"]).total_seconds()
    return latest["Size"] > 0 and -300 <= age <= MAX_AGE_SECONDS


def policy_is_valid(s3, bucket, account):
    arguments = {"Bucket": bucket, "ExpectedBucketOwner": account}
    versioning = s3.get_bucket_versioning(**arguments)
    if versioning.get("Status") in ("Enabled", "Suspended"):
        return False
    rules = s3.get_bucket_lifecycle_configuration(**arguments).get("Rules", [])
    valid_rule = any(
        rule.get("ID") == "toadzip-db-backup-30d"
        and rule.get("Status") == "Enabled"
        and (rule.get("Filter") == {"Prefix": "backups/"}
             or ("Filter" not in rule and rule.get("Prefix") == "backups/"))
        and rule.get("Expiration") == {"Days": 30}
        for rule in rules
    )
    if not valid_rule:
        return False
    block = s3.get_public_access_block(**arguments)["PublicAccessBlockConfiguration"]
    if not all(block.get(key) is True for key in (
        "BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets"
    )):
        return False
    encryption = s3.get_bucket_encryption(**arguments)["ServerSideEncryptionConfiguration"]["Rules"]
    return any(rule.get("ApplyServerSideEncryptionByDefault", {}).get("SSEAlgorithm") == "AES256"
               for rule in encryption)


def check_health(s3, cloudwatch, bucket, account, now):
    health = {}
    for service in (*SERVICES, "bucket-policy"):
        try:
            if service == "bucket-policy":
                healthy = policy_is_valid(s3, bucket, account)
            else:
                healthy = backup_is_fresh(s3, bucket, account, service, now)
            health[service] = int(healthy)
        except Exception:
            # A permissions/API failure is unhealthy; keep AWS responses and DB contents out of logs.
            LOGGER.error("Backup health could not be checked: %s", service)
            health[service] = 0
    cloudwatch.put_metric_data(
        Namespace="Toadzip/DatabaseBackup",
        MetricData=[{
            "MetricName": "Healthy", "Value": value, "Unit": "Count",
            "Dimensions": [{"Name": "Bucket", "Value": bucket}, {"Name": "Service", "Value": service}]
        } for service, value in health.items()]
    )
    return health


def handler(event, context):
    import boto3
    return check_health(boto3.client("s3"), boto3.client("cloudwatch"),
                        os.environ["BACKUP_BUCKET"], os.environ["BACKUP_ACCOUNT"],
                        datetime.now(timezone.utc))

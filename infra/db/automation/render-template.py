"""Render a self-contained CloudFormation template using only Python's standard library."""
import json
from pathlib import Path


def template():
    ref = lambda name: {"Ref": name}
    sub = lambda value: {"Fn::Sub": value}
    attr = lambda name, key: {"Fn::GetAtt": [name, key]}
    bucket_arn = attr("BackupBucket", "Arn")
    bucket_actions = ["s3:ListBucket", "s3:GetBucketVersioning", "s3:GetLifecycleConfiguration",
                      "s3:GetBucketPublicAccessBlock", "s3:GetEncryptionConfiguration"]
    read_policy = {"Effect": "Allow", "Action": bucket_actions, "Resource": bucket_arn}
    resources = {
        "BackupBucket": {"Type": "AWS::S3::Bucket", "DeletionPolicy": "Retain", "UpdateReplacePolicy": "Retain",
            "Properties": {
                "OwnershipControls": {"Rules": [{"ObjectOwnership": "BucketOwnerEnforced"}]},
                "PublicAccessBlockConfiguration": {key: True for key in (
                    "BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets")},
                "BucketEncryption": {"ServerSideEncryptionConfiguration": [{
                    "ServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]},
                "LifecycleConfiguration": {"Rules": [{"Id": "toadzip-db-backup-30d", "Status": "Enabled",
                    "Prefix": "backups/", "ExpirationInDays": 30,
                    "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 7}}]}}},
        "Alerts": {"Type": "AWS::SNS::Topic", "Properties": {"Subscription": [{
            "Protocol": "email", "Endpoint": ref("AlertEmail")}] }},
        "AlertPolicy": {"Type": "AWS::SNS::TopicPolicy", "Properties": {
            "Topics": [ref("Alerts")], "PolicyDocument": {"Version": "2012-10-17", "Statement": [{
                "Effect": "Allow", "Principal": {"Service": "cloudwatch.amazonaws.com"},
                "Action": "sns:Publish", "Resource": ref("Alerts"), "Condition": {
                    "StringEquals": {"aws:SourceAccount": ref("AWS::AccountId")},
                    "ArnLike": {"aws:SourceArn": sub("arn:${AWS::Partition}:cloudwatch:${AWS::Region}:${AWS::AccountId}:alarm:*")}
                }}]}}},
        "BackupServerPolicy": {"Type": "AWS::IAM::ManagedPolicy", "Properties": {
            "Roles": [ref("BackupInstanceRoleName")], "PolicyDocument": {"Version": "2012-10-17", "Statement": [
                read_policy,
                {"Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload"],
                 "Resource": sub("${BackupBucket.Arn}/backups/*")},
                {"Effect": "Allow", "Action": "sns:Publish", "Resource": ref("Alerts")}]}}},
        "MonitorRole": {"Type": "AWS::IAM::Role", "Properties": {
            "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
                "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]},
            "Policies": [{"PolicyName": "ReadBackupHealth", "PolicyDocument": {"Version": "2012-10-17", "Statement": [
                read_policy,
                {"Effect": "Allow", "Action": "cloudwatch:PutMetricData", "Resource": "*",
                 "Condition": {"StringEquals": {"cloudwatch:namespace": "Toadzip/DatabaseBackup"}}},
                {"Effect": "Allow", "Action": ["logs:CreateLogStream", "logs:PutLogEvents"],
                 "Resource": sub("arn:${AWS::Partition}:logs:${AWS::Region}:${AWS::AccountId}:log-group:/aws/lambda/*:*")}
            ]}}]}},
        "Monitor": {"Type": "AWS::Lambda::Function", "Properties": {
            "Runtime": "python3.14", "Handler": "index.handler", "Timeout": 120,
            "Role": attr("MonitorRole", "Arn"),
            "Environment": {"Variables": {"BACKUP_BUCKET": ref("BackupBucket"), "BACKUP_ACCOUNT": ref("AWS::AccountId")}},
            "Code": {"ZipFile": Path(__file__).with_name("handler.py").read_text(encoding="utf-8")}}},
        "MonitorLogs": {"Type": "AWS::Logs::LogGroup", "Properties": {
            "LogGroupName": sub("/aws/lambda/${Monitor}"), "RetentionInDays": 30}},
        "MonitorSchedule": {"Type": "AWS::Events::Rule", "DependsOn": ["MonitorLogs"], "Properties": {
            "ScheduleExpression": "rate(1 hour)", "State": "ENABLED",
            "Targets": [{"Arn": attr("Monitor", "Arn"), "Id": "BackupHealth"}]}},
        "InvokeMonitor": {"Type": "AWS::Lambda::Permission", "Properties": {
            "Action": "lambda:InvokeFunction", "FunctionName": ref("Monitor"),
            "Principal": "events.amazonaws.com", "SourceArn": attr("MonitorSchedule", "Arn")}}
    }
    for logical_name, service in (("ProdAlarm", "db-prod"), ("DevAlarm", "db-dev"),
                                  ("SharedAlarm", "db-shared"), ("PolicyAlarm", "bucket-policy")):
        resources[logical_name] = {"Type": "AWS::CloudWatch::Alarm", "Properties": {
            "AlarmDescription": f"Toadzip backup unhealthy: {service}; check DB backup job and S3 policy.",
            "Namespace": "Toadzip/DatabaseBackup", "MetricName": "Healthy",
            "Dimensions": [{"Name": "Bucket", "Value": ref("BackupBucket")}, {"Name": "Service", "Value": service}],
            "Statistic": "Minimum", "Period": 3600, "EvaluationPeriods": 2, "DatapointsToAlarm": 2,
            "Threshold": 1, "ComparisonOperator": "LessThanThreshold", "TreatMissingData": "breaching",
            "AlarmActions": [ref("Alerts")], "OKActions": [ref("Alerts")]}}
    return {"AWSTemplateFormatVersion": "2010-09-09", "Description": "Toadzip daily DB backup storage and independent monitoring",
        "Parameters": {
            "BackupInstanceRoleName": {"Type": "String", "AllowedPattern": "[A-Za-z0-9+=,.@_-]+",
                "Description": "Existing IAM role attached to the DB EC2 instance"},
            "AlertEmail": {"Type": "String", "Default": "toadzip.official@gmail.com"}},
        "Resources": resources, "Outputs": {
            "BackupBucketName": {"Value": ref("BackupBucket")}, "AlertTopicArn": {"Value": ref("Alerts")},
            "MonitorFunctionName": {"Value": ref("Monitor")}}}


if __name__ == "__main__":
    print(json.dumps(template(), indent=2))

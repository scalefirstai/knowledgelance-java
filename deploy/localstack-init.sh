#!/bin/bash
# LocalStack ready hook: bucket, SQS change feed wired to S3 notifications, sample bundles.
set -euo pipefail
awslocal s3 mb s3://kl-knowledge
awslocal sqs create-queue --queue-name kl-bundle-events >/dev/null
ARN=arn:aws:sqs:us-east-1:000000000000:kl-bundle-events
awslocal s3api put-bucket-notification-configuration --bucket kl-knowledge --notification-configuration \
  '{"QueueConfigurations":[{"QueueArn":"'"$ARN"'","Events":["s3:ObjectCreated:*","s3:ObjectRemoved:*"],
    "Filter":{"Key":{"FilterRules":[{"Name":"prefix","Value":"bundles/"}]}}}]}'
awslocal s3 sync /seed s3://kl-knowledge/bundles >/dev/null
touch /tmp/kl-seeded

output "bucket" { value = aws_s3_bucket.kl.bucket }
output "kms_key_arn" { value = aws_kms_key.kl.arn }
output "queue_url" { value = aws_sqs_queue.events.url }
output "role_arns" { value = { for k, r in aws_iam_role.role : k => r.arn } }

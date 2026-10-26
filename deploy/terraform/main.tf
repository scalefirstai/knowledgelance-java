# KnowledgeLake AWS footprint: one SSE-KMS bucket, an SQS change feed fed by S3 notifications,
# and least-privilege roles. Connectors write only bundles/<their prefix>/; the indexer is the only
# writer of _kl/; query pods only read _kl/ (restricted partitions get their own query role).

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 5.0" }
  }
}

data "aws_caller_identity" "current" {}

locals {
  bucket_arn = "arn:aws:s3:::${var.bucket_name}"
  irsa       = var.eks_oidc_provider_arn != ""
}

# ----------------------------------------------------------------------------- KMS + bucket

resource "aws_kms_key" "kl" {
  description         = "${var.name} bundles and indexes"
  enable_key_rotation = true
  tags                = var.tags
}

resource "aws_s3_bucket" "kl" {
  bucket = var.bucket_name
  tags   = var.tags
}

resource "aws_s3_bucket_versioning" "kl" {
  bucket = aws_s3_bucket.kl.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "kl" {
  bucket = aws_s3_bucket.kl.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.kl.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "kl" {
  bucket                  = aws_s3_bucket.kl.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Lucene commit files are pruned by `kl optimize`; this expires the noncurrent S3 object versions.
resource "aws_s3_bucket_lifecycle_configuration" "kl" {
  bucket = aws_s3_bucket.kl.id
  rule {
    id     = "expire-noncurrent-index-versions"
    status = "Enabled"
    filter { prefix = "_kl/" }
    noncurrent_version_expiration { noncurrent_days = 7 }
  }
}

# ----------------------------------------------------------------------------- change feed

resource "aws_sqs_queue" "dlq" {
  name                      = "${var.name}-bundle-events-dlq"
  message_retention_seconds = 1209600
  kms_master_key_id         = aws_kms_key.kl.arn
  tags                      = var.tags
}

resource "aws_sqs_queue" "events" {
  name                       = "${var.name}-bundle-events"
  visibility_timeout_seconds = 300
  receive_wait_time_seconds  = 20
  kms_master_key_id          = aws_kms_key.kl.arn
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.dlq.arn, maxReceiveCount = 5 })
  tags                       = var.tags
}

resource "aws_sqs_queue_policy" "events" {
  queue_url = aws_sqs_queue.events.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.events.arn
      Condition = { ArnEquals = { "aws:SourceArn" = local.bucket_arn } }
    }]
  })
}

# S3 needs to use the KMS key to publish to the encrypted queue
resource "aws_kms_key_policy" "kl" {
  key_id = aws_kms_key.kl.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "Root"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "S3Notifications"
        Effect    = "Allow"
        Principal = { Service = "s3.amazonaws.com" }
        Action    = ["kms:GenerateDataKey", "kms:Decrypt"]
        Resource  = "*"
      }
    ]
  })
}

resource "aws_s3_bucket_notification" "events" {
  bucket = aws_s3_bucket.kl.id
  queue {
    queue_arn     = aws_sqs_queue.events.arn
    events        = ["s3:ObjectCreated:*", "s3:ObjectRemoved:*"]
    filter_prefix = "bundles/"
    filter_suffix = ".md"
  }
  depends_on = [aws_sqs_queue_policy.events]
}

# ----------------------------------------------------------------------------- roles

data "aws_iam_policy_document" "assume" {
  for_each = toset(concat(["indexer", "query"], [for p in var.restricted_partitions : "query-${p}"], [for c in var.connector_prefixes : "connector-${c}"]))

  statement {
    actions = local.irsa ? ["sts:AssumeRoleWithWebIdentity"] : ["sts:AssumeRole"]
    dynamic "principals" {
      for_each = local.irsa ? [1] : []
      content {
        type        = "Federated"
        identifiers = [var.eks_oidc_provider_arn]
      }
    }
    dynamic "principals" {
      for_each = local.irsa ? [] : [1]
      content {
        type        = "AWS"
        identifiers = var.trusted_principal_arns
      }
    }
    dynamic "condition" {
      for_each = local.irsa ? [1] : []
      content {
        test     = "StringEquals"
        variable = "${var.eks_oidc_provider_url}:sub"
        values   = ["system:serviceaccount:${var.namespace}:${var.name}-${each.key}"]
      }
    }
  }
}

resource "aws_iam_role" "role" {
  for_each           = data.aws_iam_policy_document.assume
  name               = "${var.name}-${each.key}"
  assume_role_policy = each.value.json
  tags               = var.tags
}

data "aws_iam_policy_document" "indexer" {
  statement {
    sid       = "ListBucket"
    actions   = ["s3:ListBucket"]
    resources = [local.bucket_arn]
  }
  statement {
    sid       = "ReadBundles"
    actions   = ["s3:GetObject"]
    resources = ["${local.bucket_arn}/bundles/*"]
  }
  statement {
    sid       = "OwnIndexes"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${local.bucket_arn}/_kl/*"]
  }
  statement {
    sid       = "ConsumeEvents"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"]
    resources = [aws_sqs_queue.events.arn]
  }
  statement {
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [aws_kms_key.kl.arn]
  }
}

# Main query role: reads _kl/lucene/main and _kl/state only, never a restricted partition.
# A restricted query role (below) reads the main partition plus its own.
data "aws_iam_policy_document" "query" {
  statement {
    actions   = ["s3:ListBucket"]
    resources = [local.bucket_arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["_kl/lucene/main/*", "_kl/state/*", "_kl/lucene/main/", "_kl/state/"]
    }
  }
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${local.bucket_arn}/_kl/lucene/main/*", "${local.bucket_arn}/_kl/state/*"]
  }
  statement {
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.kl.arn]
  }
}

data "aws_iam_policy_document" "query_restricted" {
  for_each = toset(var.restricted_partitions)
  statement {
    actions   = ["s3:ListBucket"]
    resources = [local.bucket_arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["_kl/lucene/${each.key}/*", "_kl/lucene/main/*", "_kl/state/*", "_kl/lucene/${each.key}/", "_kl/lucene/main/", "_kl/state/"]
    }
  }
  statement {
    actions = ["s3:GetObject"]
    resources = [
      "${local.bucket_arn}/_kl/lucene/${each.key}/*",
      "${local.bucket_arn}/_kl/lucene/main/*",
      "${local.bucket_arn}/_kl/state/*",
    ]
  }
  statement {
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.kl.arn]
  }
}

data "aws_iam_policy_document" "connector" {
  for_each = toset(var.connector_prefixes)
  statement {
    actions   = ["s3:ListBucket"]
    resources = [local.bucket_arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["bundles/${each.key}/*", "bundles/${each.key}/"]
    }
  }
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${local.bucket_arn}/bundles/${each.key}/*"]
  }
  statement {
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [aws_kms_key.kl.arn]
  }
}

resource "aws_iam_role_policy" "indexer" {
  role   = aws_iam_role.role["indexer"].id
  policy = data.aws_iam_policy_document.indexer.json
}

resource "aws_iam_role_policy" "query" {
  role   = aws_iam_role.role["query"].id
  policy = data.aws_iam_policy_document.query.json
}

resource "aws_iam_role_policy" "query_restricted" {
  for_each = data.aws_iam_policy_document.query_restricted
  role     = aws_iam_role.role["query-${each.key}"].id
  policy   = each.value.json
}

resource "aws_iam_role_policy" "connector" {
  for_each = data.aws_iam_policy_document.connector
  role     = aws_iam_role.role["connector-${each.key}"].id
  policy   = each.value.json
}

# Defense in depth: nobody but the indexer role may write _kl/.
resource "aws_s3_bucket_policy" "kl" {
  bucket = aws_s3_bucket.kl.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "OnlyIndexerWritesIndexes"
        Effect    = "Deny"
        Principal = "*"
        Action    = ["s3:PutObject", "s3:DeleteObject"]
        Resource  = "${local.bucket_arn}/_kl/*"
        Condition = { ArnNotEquals = { "aws:PrincipalArn" = aws_iam_role.role["indexer"].arn } }
      },
      {
        Sid       = "TLSOnly"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [local.bucket_arn, "${local.bucket_arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      }
    ]
  })
  depends_on = [aws_s3_bucket_public_access_block.kl]
}

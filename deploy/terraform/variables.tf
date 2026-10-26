variable "name" {
  description = "Resource name prefix"
  type        = string
  default     = "kl"
}

variable "bucket_name" {
  description = "Bucket holding bundles/ (written by connectors) and _kl/ (owned by KnowledgeLake)"
  type        = string
}

variable "connector_prefixes" {
  description = "Bundle prefixes connectors may write, e.g. [\"jira\", \"confluence\"]; one IAM role each"
  type        = list(string)
  default     = ["jira", "confluence"]
}

variable "restricted_partitions" {
  description = "Isolated partitions (under _kl/lucene/<name>/) readable only by their own query role"
  type        = list(string)
  default     = []
}

variable "eks_oidc_provider_arn" {
  description = "EKS OIDC provider ARN for IRSA; leave empty to trust var.trusted_principal_arns instead"
  type        = string
  default     = ""
}

variable "eks_oidc_provider_url" {
  description = "EKS OIDC issuer URL without https://"
  type        = string
  default     = ""
}

variable "namespace" {
  description = "Kubernetes namespace for the service accounts"
  type        = string
  default     = "knowledgelake"
}

variable "trusted_principal_arns" {
  description = "Principals that may assume the roles when IRSA is not used"
  type        = list(string)
  default     = []
}

variable "tags" {
  type    = map(string)
  default = {}
}

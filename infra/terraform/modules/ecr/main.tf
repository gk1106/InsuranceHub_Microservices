# One repository per service - testing-and-deploy.md §2: images tagged with the git SHA, never
# latest, in CI. scan_on_push catches known-CVE base images before they're ever deployed.

resource "aws_ecr_repository" "this" {
  for_each             = toset(var.service_names)
  name                 = "${var.name_prefix}-${each.value}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  tags = var.tags
}

# Keep the last 20 tagged images (git SHAs) and expire untagged ones after 1 day - untagged
# images only ever appear from a failed/superseded push, never a real deploy target.
resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 1 day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep only the last 20 tagged images"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = 20
        }
        action = { type = "expire" }
      }
    ]
  })
}

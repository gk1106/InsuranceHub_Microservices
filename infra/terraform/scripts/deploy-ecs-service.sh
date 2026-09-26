#!/usr/bin/env bash
# Redeploys one ECS service with a new image tag: fetch its current task definition, swap in the
# new image, register a new task definition revision, point the service at it, wait for
# stability. Called once per service (hub-gateway, policy-service, claims-service) from
# .gitlab-ci.yml's deploy-dev/deploy-prod jobs - kept as a real script, not inlined into the
# YAML, so it's testable and reusable between the two environments.
set -euo pipefail

ENVIRONMENT="$1"   # dev | prod
SERVICE="$2"       # hub-gateway | policy-service | claims-service
IMAGE_URI="$3"     # full ECR image URI, including the git-SHA tag

CLUSTER="insurancehub-${ENVIRONMENT}"
FAMILY="insurancehub-${ENVIRONMENT}-${SERVICE}"

echo "Deploying ${SERVICE} to ${ENVIRONMENT}: ${IMAGE_URI}"

CURRENT_TASK_DEF=$(aws ecs describe-task-definition --task-definition "${FAMILY}" --query 'taskDefinition')

NEW_TASK_DEF=$(echo "${CURRENT_TASK_DEF}" | jq --arg IMAGE "${IMAGE_URI}" --arg NAME "${SERVICE}" '
  .containerDefinitions |= map(if .name == $NAME then .image = $IMAGE else . end)
  | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities, .registeredAt, .registeredBy)
')

NEW_TASK_DEF_ARN=$(aws ecs register-task-definition --cli-input-json "${NEW_TASK_DEF}" --query 'taskDefinition.taskDefinitionArn' --output text)

aws ecs update-service --cluster "${CLUSTER}" --service "${SERVICE}" --task-definition "${NEW_TASK_DEF_ARN}" > /dev/null

echo "Waiting for ${SERVICE} to stabilize..."
aws ecs wait services-stable --cluster "${CLUSTER}" --services "${SERVICE}"

echo "${SERVICE} deployed: ${NEW_TASK_DEF_ARN}"

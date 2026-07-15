#!/usr/bin/env bash
# Build + push the prompt-audit images to ACR. Run by Jenkins (or manually).
# Assumes `docker login` to ACR was done already (CI credential binding).
#
# Usage:  mimageVersion=0.0.2 SERVICES="web server" ops/build.sh
# Env:    ACR_REGISTRY, ACR_NAMESPACE, mimageVersion|VERSION, SERVICES(space-separated: web|server)
set -euo pipefail

REGISTRY="${ACR_REGISTRY:-cxm-develop-registry-vpc.ap-southeast-1.cr.aliyuncs.com}"
NAMESPACE="${ACR_NAMESPACE:-com.gigrt.cxmdev}"
VERSION="${mimageVersion:-${VERSION:-0.0.1}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

build_push() {
  local svc="$1" dir="$2"
  local img="${REGISTRY}/${NAMESPACE}/promptaudit-${svc}:${VERSION}"
  echo "=== build ${img} ==="
  docker build -t "${img}" "${ROOT}/${dir}"
  docker push "${img}"
  echo "pushed ${img}"
}

# Each service has its own Jenkins job (dev-promptaudit-web / dev-promptaudit-server) that sets SERVICES.
# Default builds both (manual full build).
SERVICES="${SERVICES:-web server}"
for svc in ${SERVICES}; do
  case "${svc}" in
    web)    build_push web web ;;
    server) build_push server server ;;
    *) echo "unknown service '${svc}' (expected: web|server)" >&2; exit 2 ;;
  esac
done

echo "=== images pushed: ${SERVICES} (tag=${VERSION}) ==="

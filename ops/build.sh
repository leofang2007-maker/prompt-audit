#!/usr/bin/env bash
# Build + push the single prompt-audit image to ACR. Run by Jenkins (or manually).
# The image is self-contained: server/Dockerfile builds the SPA and bakes it into the Spring Boot jar.
# Assumes `docker login` to ACR was done already (CI credential binding).
#
# Usage:  mimageVersion=0.0.2 ops/build.sh
# Env:    ACR_REGISTRY, ACR_NAMESPACE, mimageVersion|VERSION
set -euo pipefail

REGISTRY="${ACR_REGISTRY:-cxm-develop-registry-vpc.ap-southeast-1.cr.aliyuncs.com}"
NAMESPACE="${ACR_NAMESPACE:-com.gigrt.cxmdev}"
VERSION="${mimageVersion:-${VERSION:-0.0.1}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

IMG="${REGISTRY}/${NAMESPACE}/promptaudit-server:${VERSION}"
echo "=== build ${IMG} (context=repo root, dockerfile=server/Dockerfile) ==="
docker build -f "${ROOT}/server/Dockerfile" -t "${IMG}" "${ROOT}"
docker push "${IMG}"
echo "=== pushed ${IMG} ==="

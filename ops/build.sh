#!/usr/bin/env bash
# Build + push the single prompt-audit image to your container registry (run in CI or manually).
# The image is self-contained: server/Dockerfile builds the SPA and bakes it into the Spring Boot jar.
# Assumes you have already `docker login`'d to your registry.
#
# Usage:  VERSION=0.0.2 IMAGE_REGISTRY=registry.example.com IMAGE_NAMESPACE=you ops/build.sh
set -euo pipefail

REGISTRY="${IMAGE_REGISTRY:-registry.example.com}"
NAMESPACE="${IMAGE_NAMESPACE:-your-namespace}"
VERSION="${VERSION:-0.0.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

IMG="${REGISTRY}/${NAMESPACE}/promptaudit-server:${VERSION}"
echo "=== build ${IMG} (context=repo root, dockerfile=server/Dockerfile) ==="
docker build -f "${ROOT}/server/Dockerfile" -t "${IMG}" "${ROOT}"
docker push "${IMG}"
echo "=== pushed ${IMG} ==="

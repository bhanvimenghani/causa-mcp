#!/bin/bash
set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Usage function
usage() {
    local exit_code="${1:-1}"
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Build and push the causa-mcp container image with multi-architecture support."
    echo ""
    echo "Options:"
    echo "  -i IMAGE_NAME    Full image name (registry/repository:tag)"
    echo "  -r REGISTRY      Container registry (default: quay.io)"
    echo "  -n REPO_NAME     Repository name (default: causa-ai-hub/causa-mcp)"
    echo "  -t TAG           Image tag (default: version from pom.xml)"
    echo "  -b BUILD         Build image true/false (default: true)"
    echo "  -p PUSH          Push image true/false (default: false)"
    echo "  -l PLATFORMS     Target platforms (default: linux/amd64,linux/arm64)"
    echo "  -s SKIP_TESTS    Skip tests during Maven build true/false (default: true)"
    echo "  -h               Show this help message"
    echo ""
    echo "Environment Variables (alternative to flags):"
    echo "  IMAGE_NAME       Full image name"
    echo "  REGISTRY         Container registry"
    echo "  REPO_NAME        Repository name"
    echo "  IMAGE_TAG        Image tag"
    echo "  BUILD_IMAGE      Build image (true/false)"
    echo "  PUSH_IMAGE       Push image (true/false)"
    echo "  PLATFORMS        Target platforms"
    echo "  SKIP_TESTS       Skip tests (true/false)"
    echo ""
    echo "Examples:"
    echo "  # Build and push with a full image name"
    echo "  $0 -i quay.io/causa-ai-hub/causa-mcp:0.0.2 -b true -p true"
    echo ""
    echo "  # Build only (no push)"
    echo "  $0 -t 0.0.2 -b true -p false"
    echo ""
    echo "  # Build for amd64 only and push"
    echo "  $0 -t 0.0.2 -l linux/amd64 -p true"
    echo ""
    echo "Note: Command-line flags take precedence over environment variables"
    exit "${exit_code}"
}

# Validate boolean values
validate_boolean() {
    local value="$1"
    local flag="$2"
    if [[ ! "$value" =~ ^(true|false)$ ]]; then
        echo -e "${RED}Error: $flag must be 'true' or 'false', got: '$value'${NC}" >&2
        usage 1
    fi
}

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Resolve the project root relative to this script's location
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DOCKERFILE="${PROJECT_ROOT}/src/main/docker/Dockerfile.jvm"

# Resolve application version from pom.xml
resolve_app_version() {
    local pom="${PROJECT_ROOT}/pom.xml"
    local ver="latest"
    if [ -f "${pom}" ]; then
        local mvnw="${PROJECT_ROOT}/mvnw"
        local mvn_cmd="mvn"
        [ -f "${mvnw}" ] && mvn_cmd="${mvnw}"
        ver=$(cd "${PROJECT_ROOT}" && \
              ${mvn_cmd} help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null)
        ver="${ver:-latest}"
    fi
    if [[ "$ver" == *SNAPSHOT* ]]; then
        local ts
        ts=$(date -u +"%Y%m%d%H%M%S")
        ver="${ver}-${ts}"
    fi
    echo "$ver"
}

# Default values
REGISTRY="${REGISTRY:-quay.io}"
REPO_NAME="${REPO_NAME:-causa-ai-hub/causa-mcp}"
IMAGE_TAG="${IMAGE_TAG:-$(resolve_app_version)}"
BUILD_IMAGE="${BUILD_IMAGE:-true}"
PUSH_IMAGE="${PUSH_IMAGE:-false}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
SKIP_TESTS="${SKIP_TESTS:-true}"
IMAGE_NAME="${IMAGE_NAME:-}"

# Parse command-line arguments (override env vars)
while getopts "i:r:n:t:b:p:l:s:h" opt; do
    case ${opt} in
        i ) IMAGE_NAME="$OPTARG" ;;
        r ) REGISTRY="$OPTARG" ;;
        n ) REPO_NAME="$OPTARG" ;;
        t ) IMAGE_TAG="$OPTARG" ;;
        b ) BUILD_IMAGE="$OPTARG" ;;
        p ) PUSH_IMAGE="$OPTARG" ;;
        l ) PLATFORMS="$OPTARG" ;;
        s ) SKIP_TESTS="$OPTARG" ;;
        h ) usage 0 ;;
        \? )
            print_error "Invalid option: -$OPTARG"
            usage 1
            ;;
    esac
done

# Validate booleans
validate_boolean "$BUILD_IMAGE" "BUILD_IMAGE (-b)"
validate_boolean "$PUSH_IMAGE"  "PUSH_IMAGE (-p)"
validate_boolean "$SKIP_TESTS"  "SKIP_TESTS (-s)"

# Construct image name if not provided
if [ -z "$IMAGE_NAME" ]; then
    IMAGE_NAME="${REGISTRY}/${REPO_NAME}:${IMAGE_TAG}"
fi

# Validate project structure
if [ ! -f "${PROJECT_ROOT}/pom.xml" ]; then
    print_error "pom.xml not found at ${PROJECT_ROOT}."
    exit 1
fi

if [ ! -f "${PROJECT_ROOT}/mvnw" ]; then
    print_error "Maven wrapper (mvnw) not found at ${PROJECT_ROOT}."
    exit 1
fi

if [ ! -f "${DOCKERFILE}" ]; then
    print_error "Dockerfile not found at ${DOCKERFILE}."
    exit 1
fi

cd "${PROJECT_ROOT}"
chmod +x ./mvnw

# Display configuration
echo ""
print_info "=== Build Configuration ==="
print_info "Image Name:  ${IMAGE_NAME}"
print_info "Platforms:   ${PLATFORMS}"
print_info "Build:       ${BUILD_IMAGE}"
print_info "Push:        ${PUSH_IMAGE}"
print_info "Skip Tests:  ${SKIP_TESTS}"
echo ""

if [ "$PUSH_IMAGE" = "true" ]; then
    print_warn "Push is enabled. Image will be pushed to registry."
    print_warn "Make sure you are authenticated to ${REGISTRY}"
    echo ""
fi

# ── Step 1: Maven build ──────────────────────────────────────────────────────

if [ "$BUILD_IMAGE" = "true" ]; then
    MAVEN_CMD="./mvnw clean package"
    if [ "$SKIP_TESTS" = "true" ]; then
        MAVEN_CMD="${MAVEN_CMD} -DskipTests"
    fi

    print_info "Building JAR..."
    echo "${MAVEN_CMD}"
    echo ""
    if ! eval "${MAVEN_CMD}"; then
        print_error "Maven build failed."
        exit 1
    fi
    print_info "JAR built successfully."
    echo ""

    # ── Step 2: Docker image build ───────────────────────────────────────────

    # Determine number of platforms requested
    PLATFORM_COUNT=$(echo "${PLATFORMS}" | tr ',' '\n' | wc -l | tr -d ' ')

    if [ "${PLATFORM_COUNT}" -gt 1 ]; then
        # Multi-arch: requires buildx
        if ! docker buildx version &>/dev/null; then
            print_error "docker buildx is not available. Install Docker Buildx to build multi-arch images."
            exit 1
        fi

        BUILDX_CMD="docker buildx build --platform ${PLATFORMS} -f ${DOCKERFILE} -t ${IMAGE_NAME} --provenance=false"
        if [ "$PUSH_IMAGE" = "true" ]; then
            BUILDX_CMD="${BUILDX_CMD} --output type=registry"
        else
            BUILDX_CMD="${BUILDX_CMD} --load"
        fi
        BUILDX_CMD="${BUILDX_CMD} ."

        print_info "Building multi-arch image (${PLATFORMS})..."
        echo "${BUILDX_CMD}"
        echo ""
        if ! eval "${BUILDX_CMD}"; then
            print_error "docker buildx build failed."
            exit 1
        fi

        # Push already handled by --output type=registry above
        PUSH_IMAGE="false"

    else
        # Single-arch: plain docker build
        DOCKER_CMD="docker build -f ${DOCKERFILE} -t ${IMAGE_NAME} ."

        print_info "Building image for ${PLATFORMS}..."
        echo "${DOCKER_CMD}"
        echo ""
        if ! eval "${DOCKER_CMD}"; then
            print_error "docker build failed."
            exit 1
        fi
    fi

    print_info "Image built: ${IMAGE_NAME}"
    echo ""
fi

# ── Step 3: Push (single-arch path only) ────────────────────────────────────

if [ "$PUSH_IMAGE" = "true" ]; then
    print_info "Pushing ${IMAGE_NAME}..."
    if ! docker push "${IMAGE_NAME}"; then
        print_error "docker push failed. Run: docker login ${REGISTRY}"
        exit 1
    fi
    print_info "Image pushed: ${IMAGE_NAME}"
    echo ""
fi

# ── Summary ──────────────────────────────────────────────────────────────────

echo ""
print_info "=== Build Summary ==="
print_info "✓ Build completed successfully"
print_info "Image: ${IMAGE_NAME}"
print_info "Platforms: ${PLATFORMS}"
echo ""

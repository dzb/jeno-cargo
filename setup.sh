#!/bin/bash
#
# mj — modern java manager installer
# Usage: curl -fsSL https://raw.githubusercontent.com/dzb/mj-cargo/main/setup.sh | bash
#
# Downloads the mj distribution from GitHub Releases, extracts it, and
# installs to $HOME/.mj/ with PATH configuration and a /usr/local/bin symlink.

set -e

VERSION="v0.1.0"
PACKAGE_URL="https://github.com/dzb/mj-cargo/releases/download/${VERSION}/mj-bin-${VERSION#v}.tar.gz"
TEMP_DIR=""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

info() { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

cleanup() {
    if [ -n "$TEMP_DIR" ]; then
        rm -rf "$TEMP_DIR"
    fi
}
trap cleanup EXIT

# ---- check Java 25+ ----
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\)\..*/\1/' 2>/dev/null)
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 25 ]; then
    error "mj requires JDK 25+ (found: ${JAVA_MAJOR:-none})"
    echo "  download: https://adoptium.net/temurin/releases/?version=25"
    exit 1
fi
info "Java $JAVA_MAJOR found"

# ---- download ----
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/mj-install.XXXXXX")
info "downloading mj ${VERSION} ..."

if ! curl -fsSL -o "$TEMP_DIR/mj-bin.tar.gz" "$PACKAGE_URL"; then
    error "download failed: $PACKAGE_URL"
    exit 1
fi
info "download complete"

# ---- extract ----
info "extracting ..."
tar -xzf "$TEMP_DIR/mj-bin.tar.gz" -C "$TEMP_DIR"

INSTALL_SCRIPT=$(find "$TEMP_DIR" -name "install.sh" -type f | head -1)
if [ -z "$INSTALL_SCRIPT" ]; then
    error "install.sh not found in the package"
    exit 1
fi

# ---- install ----
info "installing ..."
bash "$INSTALL_SCRIPT"

echo ""
echo "mj ${VERSION} — modern java manager"
echo "  run 'mj' to see available commands"

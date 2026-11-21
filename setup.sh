#!/bin/bash
#
# mj — modern java manager installer
# Usage: curl -fsSL https://raw.githubusercontent.com/dzb/mj-cargo/master/setup.sh | bash
#
# Resolves the latest release dynamically from GitHub — no version hardcoding,
# so this script never needs editing when a new version ships. Downloads the
# release tar.gz, extracts it, and delegates to the setup.sh inside to install
# to $HOME/.mj/.

set -e

REPO="dzb/mj-cargo"
TEMP_DIR=""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

info() { echo -e "${GREEN}[INFO]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

cleanup() {
    if [ -n "$TEMP_DIR" ]; then
        rm -rf "$TEMP_DIR"
    fi
}
trap cleanup EXIT

# ---- resolve the latest release tag ----
# Follow the redirect from releases/latest to releases/tag/vX.Y.Z — one HEAD
# request, no API rate limits, no JSON parsing.
LATEST=$(curl -fsIL "https://github.com/$REPO/releases/latest" -o /dev/null -w '%{url_effective}' 2>/dev/null | sed 's|.*/tag/||')
if [ -z "$LATEST" ] || [ "$LATEST" = "latest" ]; then
    error "cannot resolve the latest release — check your network connection"
    exit 1
fi
VERSION="${LATEST#v}"
info "latest release: $LATEST"

PACKAGE_URL="https://github.com/$REPO/releases/download/$LATEST/mj-bin-$VERSION.tar.gz"

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
info "downloading mj $LATEST ..."

if ! curl -fsSL -o "$TEMP_DIR/mj-bin.tar.gz" "$PACKAGE_URL"; then
    error "download failed: $PACKAGE_URL"
    exit 1
fi
info "download complete"

# ---- extract ----
info "extracting ..."
tar -xzf "$TEMP_DIR/mj-bin.tar.gz" -C "$TEMP_DIR"

SETUP_SCRIPT=$(find "$TEMP_DIR" -name "setup.sh" -type f | head -1)
if [ -z "$SETUP_SCRIPT" ]; then
    error "setup.sh not found in the package"
    exit 1
fi

# ---- install (delegates to the setup.sh inside the distribution) ----
info "installing ..."
bash "$SETUP_SCRIPT"

echo ""
echo "mj $LATEST — modern java manager"
echo "  run 'mj' to see available commands"
echo "  run 'mj update' to upgrade to a newer version"

#!/bin/sh
set -e

# Intercept codesign to pre-clear iCloud xattrs (macOS 15+ + iCloud Drive issue)
export PATH="/tmp/codesign_shim:$PATH"
exec /bin/sh "$FLUTTER_ROOT/packages/flutter_tools/bin/xcode_backend.sh" build

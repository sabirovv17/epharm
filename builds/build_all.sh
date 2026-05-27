#!/bin/sh
# Epharm — пересобрать APK + iOS .app bundle.
# Кладёт артефакты в builds/. Версия читается из pubspec.yaml.

set -e

export PATH="$HOME/development/flutter/bin:$PATH"
cd "$(dirname "$0")/.."

VERSION=$(grep "^version:" pubspec.yaml | awk '{print $2}' | sed 's/+.*//')
echo "▶ Building Epharm v${VERSION}"

# Воссоздать codesign shim (после reboot Mac /tmp обнуляется → shim пропадает,
# и Flutter не может подписать Flutter.framework для simulator/iCloud-папки).
# См. claude-notes.md → раздел «iOS codesign fix».
mkdir -p /tmp/codesign_shim
cat > /tmp/codesign_shim/codesign << 'SHIM'
#!/bin/sh
exec /usr/bin/codesign --no-strict "$@"
SHIM
chmod +x /tmp/codesign_shim/codesign

flutter clean
flutter pub get

# ─── Android APK release (универсальный, все архитектуры) ──────────────────
echo "▶ flutter build apk --release"
flutter build apk --release
cp build/app/outputs/flutter-apk/app-release.apk \
   "builds/Epharm-v${VERSION}-release.apk"

# ─── iOS .app bundle (без distribution-подписи) ────────────────────────────
# Для подписанной IPA нужен платный Apple Developer аккаунт + provisioning
# profile; если он есть — запусти `flutter build ipa --release` вручную.
echo "▶ flutter build ios --release --no-codesign"
flutter build ios --release --no-codesign
( cd build/ios/iphoneos && zip -qr \
    "../../../builds/Epharm-v${VERSION}-Runner.app.zip" Runner.app )

echo "✓ Done. Artifacts in builds/:"
ls -lh builds/ | grep -v "^total\|README\|build_all"

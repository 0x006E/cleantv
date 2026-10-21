#!/usr/bin/env bash
# One-time migration: re-install CleanTV signed with the release key while preserving
# all app data (STB config, channel favourites, custom LCNs, companion pairing keys).
#
# Run ONCE when upgrading from a debug-key build (v0.2 and earlier) to a release-key build.
# After this, all future installs can use the normal build-and-install.sh with -r (replace).
#
# Usage:
#   TV_HOST=100.97.35.51:5555 ./tools/migrate-release-key.sh
set -euo pipefail
cd "$(dirname "$0")/.."

: "${TV_HOST:?set TV_HOST, e.g. TV_HOST=100.97.35.51:5555 $0}"

ADB="adb -s $TV_HOST"
PKG="dev.ntsv.cleantv"
BACKUP_DIR="/sdcard/Download"
BACKUP_FILE="$BACKUP_DIR/cleantv_migration_backup.json"
LOCAL_BACKUP="./cleantv_migration_backup.json"
APK="tv-launcher/app/build/outputs/apk/release/app-release.apk"

echo "==> Checking TV connection..."
$ADB shell echo "TV connected" || { echo "ERROR: cannot reach TV at $TV_HOST"; exit 1; }

echo "==> Building latest release APK..."
export JAVA_HOME="${JAVA_HOME:-/nix/store/00qkmlz3vcydw9si4k3lzsrslpqbcprr-openjdk-21.0.3+9}"
export PATH="$JAVA_HOME/bin:$PATH"
cd tv-launcher && ./gradlew assembleRelease -q --console=plain && cd ..

echo "==> Triggering in-app settings export on TV (via broadcast)..."
# First try to export via adb shell broadcast to the app's backup content.
# The app writes to /sdcard/Download/cleantv_settings.json
$ADB shell am broadcast -a dev.ntsv.cleantv.ACTION_EXPORT_SETTINGS 2>/dev/null || true

echo "==> Pulling SharedPreferences directly as backup..."
# Pull the raw SharedPreferences XML files — these hold all settings.
mkdir -p /tmp/cleantv_prefs_backup
$ADB shell "run-as $PKG sh -c 'cp -r /data/data/$PKG/shared_prefs /sdcard/Download/cleantv_prefs_bak 2>/dev/null; echo done'" 2>/dev/null \
  && $ADB pull /sdcard/Download/cleantv_prefs_bak /tmp/cleantv_prefs_backup/ \
  && echo "  SharedPreferences backed up to /tmp/cleantv_prefs_backup/" \
  || echo "  (run-as not available on this build; use the in-app Export Settings instead and proceed manually)"

echo ""
echo "==> Also pulling the app's exported settings file if present..."
$ADB pull "$BACKUP_DIR/cleantv_settings.json" "$LOCAL_BACKUP" 2>/dev/null && \
  echo "  Settings exported to $LOCAL_BACKUP" || \
  echo "  No cleantv_settings.json found on TV — use in-app Export Settings before proceeding."

echo ""
read -p "Are your settings backed up? Type YES to continue with uninstall: " CONFIRM
[ "$CONFIRM" = "YES" ] || { echo "Aborted."; exit 1; }

echo "==> Uninstalling old debug-signed APK (data will be wiped by Android)..."
$ADB shell pm uninstall "$PKG" || true

echo "==> Installing new release-signed APK..."
$ADB install "$APK"
echo "  Install complete."

echo "==> Restoring permissions and setting as Home..."
$ADB shell "pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted"
$ADB shell "cmd package compile -m speed -f $PKG"
$ADB shell "cmd package set-home-activity --user 0 $PKG/.MainActivity"

echo ""
echo "==> Restore your settings:"
echo "    On the TV: navigate to TV Home Settings -> Import Settings from File"
echo "    This reads from /sdcard/Download/cleantv_settings.json"
echo ""

if [ -f "/tmp/cleantv_prefs_backup/cleantv_prefs_bak" ]; then
  echo "    Raw SharedPreferences were also backed up to /tmp/cleantv_prefs_backup/"
  echo "    These can be restored manually with adb if needed."
fi

echo "Migration complete. Launch CleanTV and restore settings from the settings screen."

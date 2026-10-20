#!/usr/bin/env bash
# ==============================================================================
# Xiaomi Mi TV 4A (2019) Clean Launcher & Sensy IR Installer
# ==============================================================================
set -euo pipefail

TV_IP="${1:-}"

if [ -z "$TV_IP" ]; then
    echo "Usage: ./tools/install-mitv4a.sh <TV_IP_ADDRESS>"
    echo "Example: ./tools/install-mitv4a.sh 192.168.1.50"
    exit 1
fi

echo "==> 1. Connecting to Xiaomi Mi TV 4A at $TV_IP:5555..."
adb disconnect || true
adb connect "$TV_IP:5555"

echo "==> 2. Installing Clean Native TV Launcher..."
APK_PATH="tv-launcher/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_PATH" ]; then
    APK_PATH="tv-launcher/app/build/outputs/apk/debug/app-debug.apk"
fi
adb -s "$TV_IP:5555" install -r "$APK_PATH"

echo "==> 3. Enabling Xiaomi Home button intercept (HomeKeyService)..."
adb -s "$TV_IP:5555" shell settings put secure enabled_accessibility_services dev.ntsv.cleantv/.HomeKeyService
adb -s "$TV_IP:5555" shell settings put secure accessibility_enabled 1

echo "==> 4. Debloating PatchWall & Xiaomi telemetry (safe disable, reversible)..."
DEBLOAT_PKGS=(
    "com.miui.tv.analytics"
    "com.xiaomi.statistic"
    "com.mitv.tvhome.atv"
    "com.mitv.tvhome.michannel"
    "com.mitv.tvhome.mitvplus"
    "com.xm.webcontent"
    "com.xiaomi.floatingframe"
    "com.xiaomi.mitv.tvmanager"
    "com.xiaomi.mitv.smartshare"
    "com.mitv.milinkservice"
    "com.google.android.tvlauncher"
    "com.google.android.tvrecommendations"
    "com.google.android.leanbacklauncher.recommendations"
)

for pkg in "${DEBLOAT_PKGS[@]}"; do
    echo "    - Disabling $pkg"
    adb -s "$TV_IP:5555" shell pm disable-user --user 0 "$pkg" || true
done

echo "==> 5. Launching clean home..."
adb -s "$TV_IP:5555" shell cmd package set-home-activity --user 0 dev.ntsv.cleantv/.MainActivity
adb -s "$TV_IP:5555" shell am start -W -n dev.ntsv.cleantv/.MainActivity

echo ""
echo "=============================================================================="
echo "SUCCESS! Clean TV Launcher & Sensy IR Blaster Engine installed on your Mi TV."
echo "=============================================================================="
echo "Controls:"
echo "  • Top Row: AV1 Remote pill, Inputs, Wi-Fi, Settings"
echo "  • Live Channels: Click any channel to switch to AV1 & blast IR digits"
echo "  • Apps: Favorite Apps Dock + All Apps Grid"
echo "  • Remote Sidebar: Long-press Center OK or press Menu button"
echo ""
echo "To revert PatchWall anytime:"
echo "  ./tools/restore-mitv4a.sh $TV_IP"


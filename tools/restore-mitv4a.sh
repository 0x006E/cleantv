#!/usr/bin/env bash
# ==============================================================================
# Xiaomi Mi TV 4A (2019) Revert / Restore Script
# ==============================================================================
set -euo pipefail

TV_IP="${1:-}"

if [ -z "$TV_IP" ]; then
    echo "Usage: ./tools/restore-mitv4a.sh <TV_IP_ADDRESS>"
    exit 1
fi

echo "==> Re-enabling PatchWall and system packages on $TV_IP..."
adb connect "$TV_IP:5555"

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
    echo "    - Re-enabling $pkg"
    adb -s "$TV_IP:5555" shell pm enable --user 0 "$pkg" || true
done

echo "==> Restore complete."


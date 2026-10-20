#!/usr/bin/env bash
set -euo pipefail

TV_DEVICE="${1:-100.97.35.51:5555}"
echo "Applying Maximum Performance Debloat to Mi TV 4A ($TV_DEVICE)..."

PACKAGES=(
  # Xiaomi + Google Telemetry & Bug Reporting
  "com.miui.tv.analytics"
  "com.xiaomi.statistic"
  "com.google.android.feedback"
  "com.google.android.tv.bugreportsender"

  # PatchWall, Recommendations & Partner Ad Containers
  "com.mitv.tvhome.atv"
  "com.xm.webcontent"
  "com.xiaomi.floatingframe"
  "com.google.android.tvrecommendations"
  "com.google.android.leanbacklauncher.recommendations"
  "android.autoinstalls.config.xioami.dangal"
  "co.sensara.tv.mitv"

  # Xiaomi Background Services & Handbooks
  "com.xiaomi.mitv.tvmanager"
  "com.xiaomi.mitv.handbook"
  "com.xiaomi.mitv.updateservice"
  "com.xiaomo.tv.milegal"

  # Voice Assistant & Speech Services
  "com.google.android.katniss"
  "com.google.android.speech.pumpkin"

  # Casting & Miracast Services
  "com.google.android.apps.mediashell"
  "com.mitv.milinkservice"
  "com.xiaomi.mitv.smartshare"
  "com.xiaomi.wfdsinkhelperservice"

  # Preloaded Unused Xiaomi & Google Media Apps
  "com.xiaomi.mimusic2"
  "com.google.android.play.games"
  "com.google.android.videos"
  "com.mitv.gallery"
)

for pkg in "${PACKAGES[@]}"; do
  echo -n "Disabling $pkg... "
  adb -s "$TV_DEVICE" shell pm disable-user --user 0 "$pkg" || true
done

echo ""
echo "Stopping any lingering processes..."
for pkg in "${PACKAGES[@]}"; do
  adb -s "$TV_DEVICE" shell am force-stop "$pkg" 2>/dev/null || true
done

echo ""
echo "Debloat complete! Currently disabled packages:"
adb -s "$TV_DEVICE" shell pm list packages -d


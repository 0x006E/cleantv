#!/usr/bin/env bash
set -euo pipefail

TV_DEVICE="${1:-100.97.35.51:5555}"
echo "Restoring debloated packages on Mi TV 4A ($TV_DEVICE)..."

PACKAGES=(
  "com.miui.tv.analytics"
  "com.xiaomi.statistic"
  "com.google.android.feedback"
  "com.google.android.tv.bugreportsender"
  "com.mitv.tvhome.atv"
  "com.xm.webcontent"
  "com.xiaomi.floatingframe"
  "com.google.android.tvrecommendations"
  "com.google.android.leanbacklauncher.recommendations"
  "android.autoinstalls.config.xioami.dangal"
  "co.sensara.tv.mitv"
  "com.xiaomi.mitv.tvmanager"
  "com.xiaomi.mitv.handbook"
  "com.xiaomi.mitv.updateservice"
  "com.xiaomo.tv.milegal"
  "com.google.android.katniss"
  "com.google.android.speech.pumpkin"
  "com.google.android.apps.mediashell"
  "com.mitv.milinkservice"
  "com.xiaomi.mitv.smartshare"
  "com.xiaomi.wfdsinkhelperservice"
  "com.xiaomi.mimusic2"
  "com.google.android.play.games"
  "com.google.android.videos"
  "com.mitv.gallery"
)

for pkg in "${PACKAGES[@]}"; do
  echo -n "Enabling $pkg... "
  adb -s "$TV_DEVICE" shell pm enable "$pkg" || true
done

echo ""
echo "Restore complete! Currently disabled packages:"
adb -s "$TV_DEVICE" shell pm list packages -d

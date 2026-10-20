{ pkgs, lib, config, inputs, ... }:

{
  # Packages
  packages = with pkgs; [
    git
    android-tools
    qrencode
    nodejs_22
  ];

  # Languages
  languages.javascript = {
    enable = true;
    package = pkgs.nodejs_22;
  };
  languages.typescript.enable = true;
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk21;
  };

  # Android SDK & NDK
  android = {
    enable = true;
    reactNative.enable = true;
    platforms.version = [ "35" "36" ];
    buildTools.version = [ "36.0.0" ];
    systemImages.enable = false;
    emulator.enable = false;
    ndk = {
      enable = true;
      version = [ "28.0.13004108" ];
    };
  };

  enterShell = ''
    echo "============================================="
    echo "  Xiaomi / Sensy IR & STB Development Shell  "
    echo "============================================="
    echo "Node.js     : $(node --version)"
    echo "Java        : $(java -version 2>&1 | head -n 1)"
    echo "ADB         : $(adb version | head -n 1)"
    echo "ANDROID_HOME: $ANDROID_HOME"
    echo "NDK         : $ANDROID_NDK_ROOT"
    echo "Dongle      : VID 0x2717, PID 0x003d"
    echo "============================================="
  '';
}

{ pkgs, lib, config, inputs, ... }:

let
  localJdk = builtins.storePath "/nix/store/fnshd55b29s6z88mfl3ilmdqfb7l3dqi-openjdk-21.0.7+6";
  localGradle = builtins.storePath "/nix/store/wcnny936whpwnciyzkdhkqh6s4ys12ph-gradle-8.14.3";
in
{
  # Packages
  packages = with pkgs; [
    git
    android-tools
    qrencode
    nodejs_22
    localJdk
    localGradle
  ];

  # Languages
  languages.javascript = {
    enable = true;
    package = pkgs.nodejs_22;
  };
  languages.typescript.enable = true;

  # Environment
  env = {
    JAVA_HOME = "${localJdk}/lib/openjdk";
    ANDROID_HOME = "/nix/store/4l6dl66i6ak4cf5qxm4zhw3jz4gbdx8v-androidsdk/libexec/android-sdk";
    ANDROID_SDK_ROOT = "/nix/store/4l6dl66i6ak4cf5qxm4zhw3jz4gbdx8v-androidsdk/libexec/android-sdk";
    LD_LIBRARY_PATH = "/nix/store/rqsb31cxx4c3yq8y1bnjjh9995bixfcn-systemd-minimal-libs-256.8/lib";
  };

  enterShell = ''
    echo "============================================="
    echo "  Xiaomi / Sensy IR & STB Development Shell  "
    echo "============================================="
    echo "Node.js     : $(node --version)"
    echo "Java        : $(java -version 2>&1 | head -n 1)"
    echo "Gradle      : $(gradle -v 2>&1 | grep 'Gradle ')"
    echo "ADB         : $(adb version | head -n 1)"
    echo "ANDROID_HOME: $ANDROID_HOME"
    echo "Dongle      : VID 0x2717, PID 0x003d"
    echo "============================================="
  '';
}

{ pkgs, lib, config, inputs, ... }:

{
  # Packages
  packages = with pkgs; [
    git
    android-tools
    qrencode
    nodejs_22
    systemdMinimal
    libusb1
  ];

  # Languages
  languages.javascript = {
    enable = true;
    package = pkgs.nodejs_22;
  };
  languages.typescript.enable = true;

  # Environment
  env = {
    LD_LIBRARY_PATH = "${lib.makeLibraryPath [ pkgs.systemdMinimal pkgs.libusb1 ]}";
  };

  enterShell = ''
    echo "============================================="
    echo "  Xiaomi / Sensy IR & STB Development Shell  "
    echo "============================================="
    echo "Node.js : $(node --version)"
    echo "ADB     : $(adb version | head -n 1)"
    echo "Dongle  : VID 0x2717, PID 0x003d"
    echo "============================================="
  '';
}

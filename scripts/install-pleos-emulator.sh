#!/usr/bin/env bash
# Pleos Connect Emulator(v2.1.0, API 34) 시스템 이미지를 받아 AVD "PleosConnect"를 만든다.
# Android Studio의 "SDK Update Sites" 설치 절차를 명령줄로 옮긴 것이다.
#   이미지 설치/사용은 Pleos Connect SDK 라이선스 동의를 뜻한다:
#   https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml
# 사용: scripts/install-pleos-emulator.sh [RAM_MB]   (기본 4096. 공식 가이드 권장값)
set -euo pipefail
source "$(dirname "$0")/env.sh"

RAM_MB="${1:-4096}"
ZIP_URL="https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_emulator.zip"
ZIP_SHA1="b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa"
ZIP="$ANDROID_HOME/.downloads/crp_car_emulator.zip"
IMG_REL="system-images/android-34/pleos_car_emulator/x86_64"
IMG_DIR="$ANDROID_HOME/$IMG_REL"
AVD_NAME="PleosConnect"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

if [ ! -f "$IMG_DIR/system.img" ]; then
  mkdir -p "$(dirname "$ZIP")"
  if [ ! -f "$ZIP" ] || [ "$(sha1sum "$ZIP" | cut -d' ' -f1)" != "$ZIP_SHA1" ]; then
    echo "[emulator] 시스템 이미지 다운로드 (약 4GB)"
    curl -fL -C - -o "$ZIP" "$ZIP_URL"
    [ "$(sha1sum "$ZIP" | cut -d' ' -f1)" = "$ZIP_SHA1" ] || { echo "체크섬 불일치" >&2; exit 1; }
  fi
  echo "[emulator] 압축 해제 (약 20GB)"
  mkdir -p "$(dirname "$IMG_DIR")"
  rm -rf "$(dirname "$IMG_DIR")/crp_avd_out_x86_64" "$IMG_DIR"
  unzip -q -o "$ZIP" -d "$(dirname "$IMG_DIR")"
  mv "$(dirname "$IMG_DIR")/crp_avd_out_x86_64" "$IMG_DIR"
fi

echo "[emulator] AVD $AVD_NAME 생성 (2560x1440, 160dpi, RAM ${RAM_MB}MB, cold boot)"
mkdir -p "$AVD_HOME/$AVD_NAME.avd"
cat > "$AVD_HOME/$AVD_NAME.ini" <<EOF
avd.ini.encoding=UTF-8
path=$AVD_HOME/$AVD_NAME.avd
path.rel=avd/$AVD_NAME.avd
target=android-34
EOF
# 값은 시스템 이미지의 devices.xml(Pleos Connect v2.1.0 프로파일)을 따른다.
cat > "$AVD_HOME/$AVD_NAME.avd/config.ini" <<EOF
AvdId=$AVD_NAME
PlayStore.enabled=false
abi.type=x86_64
avd.ini.displayname=Pleos Connect v2.1.0
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
hw.accelerometer=no
hw.audioInput=yes
hw.audioOutput=yes
hw.battery=no
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.dPad=no
hw.device.manufacturer=42dot
hw.device.name=Pleos_Connect_v2_0
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.initialOrientation=landscape
hw.keyboard=yes
hw.lcd.density=160
hw.lcd.height=1440
hw.lcd.width=2560
hw.mainKeys=no
hw.ramSize=$RAM_MB
hw.sdCard=no
hw.sensors.orientation=no
hw.sensors.proximity=no
hw.trackBall=no
image.sysdir.1=$IMG_REL/
showDeviceFrame=no
tag.display=Pleos Connect v2.1.0 APIs
tag.id=android-automotive
vm.heapSize=256
EOF
echo "[emulator] 완료. 실행: scripts/run-emulator.sh"

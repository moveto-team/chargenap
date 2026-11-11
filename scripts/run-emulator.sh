#!/usr/bin/env bash
# AVD "PleosConnect"를 cold boot로 실행한다 (quick boot면 VHAL을 쓸 수 없다).
# 사용: scripts/run-emulator.sh [--headless] [에뮬레이터 추가 옵션...]
#   KVM을 쓸 수 없으면 소프트웨어 에뮬레이션으로 실행한다(매우 느림).
#   KVM 권한 주기(한 번, 재부팅 전까지 유효):  sudo setfacl -m u:$USER:rw /dev/kvm
#   영구 적용:                                 sudo usermod -aG kvm $USER  (다시 로그인)
set -euo pipefail
source "$(dirname "$0")/env.sh"

# apt 없이 받아 둔 X 라이브러리 (libxkbfile, libICE, libSM)
if [ -d "$HOME/.local/emu-libs/root/usr/lib/x86_64-linux-gnu" ]; then
  export LD_LIBRARY_PATH="$HOME/.local/emu-libs/root/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

ARGS=(-avd PleosConnect -no-snapshot -no-boot-anim -gpu swiftshader_indirect -netdelay none -netspeed full)
if [ "${1:-}" = "--headless" ]; then
  shift
  ARGS+=(-no-window -no-audio)
fi
if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
  echo "[emulator] KVM 가속 사용"
else
  echo "[emulator] KVM 접근 권한 없음 → 소프트웨어 에뮬레이션(-accel off). 부팅에 매우 오래 걸린다." >&2
  ARGS+=(-accel off)
fi
exec emulator "${ARGS[@]}" "$@"

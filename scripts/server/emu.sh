#!/usr/bin/env bash
# 서버에서 Pleos Connect Emulator를 창 없이 돌리고 다룬다. 화면은 adb 캡처나 로컬 PC의 SSH 터널로 본다.
#
#   scripts/server/emu.sh up          백그라운드로 cold boot, 부팅 완료까지 기다린 뒤 Virtual Console(8088) 포워딩
#   scripts/server/emu.sh status      부팅·Vehicle 서비스·car_service 상태
#   scripts/server/emu.sh install     디버그 APK를 빌드해 설치하고 실행
#   scripts/server/emu.sh shot <png>  화면 캡처 (Claude Code는 이 PNG를 읽어 화면을 확인한다)
#   scripts/server/emu.sh log         에뮬레이터 프로세스 로그 끝부분
#   scripts/server/emu.sh down        종료
#   scripts/server/emu.sh tunnel      로컬 PC에서 화면·Virtual Console을 보는 방법
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/env.sh
LOG="${EMU_LOG:-$HOME/.cache/chargenap-emulator.log}"
mkdir -p "$(dirname "$LOG")"

serial() { adb devices | awk '/^emulator-[0-9]+\tdevice/{print $1; exit}'; }
booted() { [ "$(adb -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }

case "${1:-}" in
  up)
    if [ -n "$(serial)" ]; then echo "이미 실행 중: $(serial)"; exit 0; fi
    [ -r /dev/kvm ] && [ -w /dev/kvm ] || echo "경고: /dev/kvm을 쓸 수 없다. 가속 없이 부팅하면 매우 느리다(docs/server-setup.md)" >&2
    nohup scripts/run-emulator.sh --headless >"$LOG" 2>&1 &
    PID=$!   # run-emulator.sh는 exec로 emulator가 되므로 이 PID가 에뮬레이터 프로세스다
    echo "시작 (PID $PID, 로그: $LOG). 부팅을 기다린다…"
    for _ in $(seq 1 180); do
      sleep 5
      s="$(serial || true)"
      if [ -n "$s" ] && booted "$s"; then break; fi
      if ! kill -0 "$PID" 2>/dev/null; then
        echo "에뮬레이터가 종료됐다. 로그:" >&2; tail -30 "$LOG" >&2; exit 1
      fi
    done
    s="$(serial)"; booted "$s" || { echo "15분 안에 부팅하지 못했다. scripts/server/emu.sh log" >&2; exit 1; }
    adb -s "$s" forward tcp:8088 tcp:8088 >/dev/null
    echo "부팅 완료: $s. Virtual Console: http://127.0.0.1:8088 (서버 안), scripts/vconsole.sh 사용 가능"
    ;;
  status)
    s="$(serial || true)"
    [ -n "$s" ] || { echo "실행 중인 에뮬레이터 없음"; exit 1; }
    echo "기기: $s, 부팅 완료: $(adb -s "$s" shell getprop sys.boot_completed | tr -d '\r')"
    echo "Vehicle 서비스 패키지: $(adb -s "$s" shell pm list packages ai.pleos.playground.service.vehicle | tr -d '\r' || true)"
    # 개발 PC에서 막혔던 지점(docs/progress.md '에뮬레이터 검증'): 이 문구가 계속 늘면 차량 HAL이 준비되지 않은 것
    echo "'car_service not ready' 로그 수: $(adb -s "$s" logcat -d 2>/dev/null | grep -c 'car_service not ready' || true)"
    echo "Vehicle 서비스 isActive 최근 로그:"; adb -s "$s" logcat -d 2>/dev/null | grep -i 'isActive' | tail -3 || true
    ;;
  install)
    ./gradlew assembleDebug --console=plain -q
    adb -s "$(serial)" install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s "$(serial)" shell monkey -p com.chargenap.app -c android.intent.category.LAUNCHER 1 >/dev/null
    echo "설치·실행 완료"
    ;;
  shot)
    out="${2:?저장할 PNG 경로}"
    adb -s "$(serial)" exec-out screencap -p > "$out"
    echo "$out"
    ;;
  log) tail -50 "$LOG" ;;
  down)
    s="$(serial || true)"
    [ -n "$s" ] && adb -s "$s" emu kill || echo "실행 중인 에뮬레이터 없음"
    ;;
  tunnel)
    cat <<'EOF'
로컬 PC에서 (서버의 포트를 인터넷에 열지 않고 SSH로만 연결한다):

  ssh -CN -L 8088:localhost:8088 -L 5038:localhost:5037 -L 27183:localhost:27183 dev@<서버IP>
  (이 창은 열어 둔다)

  Virtual Console:  브라우저에서 http://127.0.0.1:8088
  화면 보기·조작:   다른 터미널에서 서버의 adb 서버를 빌려 scrcpy를 실행한다 (scrcpy 공식 문서 doc/tunnels.md)
      PowerShell:   $env:ADB_SERVER_SOCKET = 'tcp:localhost:5038'; scrcpy --force-adb-forward
      bash:         ADB_SERVER_SOCKET=tcp:localhost:5038 scrcpy --force-adb-forward
EOF
    ;;
  *) sed -n '2,11p' "$0"; exit 2 ;;
esac

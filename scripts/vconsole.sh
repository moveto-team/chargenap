#!/usr/bin/env bash
# Pleos Connect Virtual Console(에뮬레이터 2.1.0+)로 차량 값을 바꾼다. 차지냅 시나리오 검증·시연용.
#
# 먼저: adb forward tcp:8088 tcp:8088   (브라우저 UI: http://127.0.0.1:8088/)
# 사용:
#   scripts/vconsole.sh gear P|R|N|D
#   scripts/vconsole.sh soc <0-100>                  배터리 잔량(%)
#   scripts/vconsole.sh charge on|off                충전 스위치
#   scripts/vconsole.sh port open|closed             충전 포트
#   scripts/vconsole.sh limit <50-100>               충전 목표(%)
#   scripts/vconsole.sh door <front_left|front_right|rear_left|rear_right> open|closed
#   scripts/vconsole.sh fan <0-8>                    공조 풍량
#   scripts/vconsole.sh temp <16-30>                 운전석 목표 온도(℃)
#   scripts/vconsole.sh range <km>                   주행 가능 거리
#   scripts/vconsole.sh recline <0-100>              운전석 등받이 위치(%)를 직접 지정
#   scripts/vconsole.sh state [jq-filter]            현재 값(JSON)
#   scripts/vconsole.sh heartbeat &                  긴 시나리오 동안 콘솔 세션 유지
#
# WSL에서 Windows 쪽 에뮬레이터를 쓸 때는 Windows의 127.0.0.1에 닿지 않으므로 PowerShell로 요청한다.
set -euo pipefail

BASE="${VCONSOLE_URL:-http://127.0.0.1:8088}"
CLIENT="chargenap-cli"
# Virtual Console은 브라우저 세션 하트비트(/browser/session, 1초 주기)가 있어야 값 주입을 받는다
# (없으면 {"error":"emulator_inactive"}). 그래서 주입 직전에 하트비트를 함께 보낸다.
HEARTBEAT="/browser/session?client=$CLIENT"

direct() { curl -s -m 2 -o /dev/null "$BASE/vehicle/catalog" 2>/dev/null; }

# request METHOD PATH  — 하트비트 후 요청하고 응답 본문을 출력한다. 실패하면 0이 아닌 값으로 끝난다.
request() {
  local method="$1" path="$2"
  if direct; then
    curl -fsS -m 10 -X POST "$BASE$HEARTBEAT" -o /dev/null
    curl -fsS -m 20 -X "$method" "$BASE$path"
  elif command -v powershell.exe >/dev/null; then
    powershell.exe -NoProfile -Command "
      [Console]::OutputEncoding = [Text.Encoding]::UTF8
      try {
        Invoke-WebRequest -UseBasicParsing -Method POST -Uri '$BASE$HEARTBEAT' -TimeoutSec 10 | Out-Null
        \$r = Invoke-WebRequest -UseBasicParsing -Method $method -Uri '$BASE$path' -TimeoutSec 20
        \$r.Content
      } catch {
        [Console]::Error.WriteLine('FAIL $path ' + \$_.ErrorDetails.Message + ' ' + \$_.Exception.Message)
        exit 1
      }" | tr -d '\r'
  else
    echo "Virtual Console($BASE)에 연결할 수 없습니다. adb forward tcp:8088 tcp:8088 을 먼저 실행하세요." >&2
    return 1
  fi
}
put() { request PUT "$1" >/dev/null && echo "OK $1"; }
bool() { case "$1" in on|open|true|1) echo true ;; off|closed|close|false|0) echo false ;; *) echo "on/off 또는 open/closed" >&2; exit 2 ;; esac; }

cmd="${1:-}"; shift || true
case "$cmd" in
  gear)
    case "${1:-}" in P|p) g=Park ;; R|r) g=Reverse ;; N|n) g=Neutral ;; D|d) g=Drive ;; *) echo "P|R|N|D" >&2; exit 2 ;; esac
    put "/vehicle/gear?gear=$g" ;;
  soc)     put "/vehicle/ev-battery?field=soc&value=$1" ;;
  charge)  put "/vehicle/ev-battery?field=charge_switch&value=$(bool "$1")" ;;
  port)    put "/vehicle/ev-battery?field=charge_port_open&value=$(bool "$1")" ;;
  limit)   put "/vehicle/ev-battery?field=percent_limit&value=$1" ;;
  door)
    case "${1:-}" in front_left) f=door_fl ;; front_right) f=door_fr ;; rear_left) f=door_rl ;; rear_right) f=door_rr ;; *) echo "door 위치" >&2; exit 2 ;; esac
    put "/vehicle/body?$f=$(bool "$2")" ;;
  fan)     put "/vehicle/hvac?fan_speed=$1" ;;
  temp)    put "/vehicle/hvac?driver_temp=$1" ;;
  range)   put "/vehicle/odometer?remaining_range_m=$(( $1 * 1000 ))" ;;
  recline) put "/vehicle/seat?kind=position&control=recline&area=driver&value=$1" ;;
  heartbeat) # 값이 계속 유지돼야 하는 긴 시나리오에서 백그라운드로 돌린다: scripts/vconsole.sh heartbeat &
    while true; do request POST "$HEARTBEAT" >/dev/null 2>&1 || true; sleep 1; done ;;
  state)
    out="$(request GET /vehicle/state)"
    if [ -n "${1:-}" ] && command -v jq >/dev/null; then echo "$out" | jq "$1"; else echo "$out"; fi ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac

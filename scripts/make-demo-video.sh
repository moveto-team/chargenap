#!/usr/bin/env bash
# 시연 영상을 만든다 (1920x1080, 한국어 자막·내레이션).
#
# 1) 프레임: 실제 화면 코드를 세션 엔진·가상 차량과 돌리며 캡처
#      ./gradlew testDebugUnitTest --tests "com.chargenap.app.ui.DemoVideoFrames" -Pchargenap.video=true
#    → app/build/demo-video/{f*.png, frames.txt, scenes.txt}
# 2) 내레이션 WAV: docs/video/narration.txt를 TTS로 읽은 NN.wav 파일들이 있는 폴더 (선택)
#      Windows: powershell -File scripts\windows\tts-narration.ps1 -Dir <폴더>
# 3) 이 스크립트: scripts/make-demo-video.sh <출력.mp4> [내레이션 WAV 폴더]
#
# 자막 글꼴: 맑은 고딕(Windows) 또는 Noto Sans CJK. FONTS_DIR로 글꼴 폴더를 바꿀 수 있다.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:?출력 파일 경로}"
NARR_DIR="${2:-}"
FRAMES="app/build/demo-video"
FONTS_DIR="${FONTS_DIR:-/mnt/c/Windows/Fonts}"
EMU_SHOT="docs/screenshots/emulator/pleos_emulator_03_charging.png"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

[ -f "$FRAMES/frames.txt" ] || { echo "프레임이 없습니다. 위 1)을 먼저 실행하세요." >&2; exit 1; }

# 에뮬레이터 실행 화면(실제 Pleos Connect Emulator 캡처, 2560x1440)을 프레임과 같은 크기로 맞춘다.
# (크기가 다른 이미지가 섞이면 concat이 그 지점에서 끊긴다)
ffmpeg -hide_banner -loglevel error -y -i "$EMU_SHOT" -vf "scale=1920:1080" "$WORK/emu.png"

# 에뮬레이터 화면을 마지막 장면 앞에 넣고 장면 시각을 다시 계산한다.
python3 - "$FRAMES" "$WORK/emu.png" "$WORK" docs/video/narration.txt <<'PY'
import sys, re, shutil, pathlib
frames_dir, emu, work, narration = map(pathlib.Path, sys.argv[1:5])
EMU_SECONDS = 5.0
lines = (frames_dir / "frames.txt").read_text().splitlines()
scenes = [l.split() for l in (frames_dir / "scenes.txt").read_text().split("\n") if l.strip()]
start = {sid: float(t) for sid, t in scenes}
# 마지막 장면(10) 시작 시각에 해당하는 프레임 앞에 넣는다.
out, t, inserted = [lines[0]], 0.0, False
entries = []
i = 1
while i < len(lines):
    if lines[i].startswith("file") and i + 1 < len(lines) and lines[i + 1].startswith("duration"):
        entries.append((lines[i].split("'")[1], float(lines[i + 1].split()[1]))); i += 2
    else:
        i += 1
last = lines[-1].split("'")[1]
for name, dur in entries:
    if not inserted and abs(t - start["10"]) < 1e-3:
        out += [f"file '{emu.resolve()}'", f"duration {EMU_SECONDS:.3f}"]
        inserted = True
        t += EMU_SECONDS
    out += [f"file '{(frames_dir / name).resolve()}'", f"duration {dur:.3f}"]
    t += dur
out.append(f"file '{(frames_dir / last).resolve()}'")
(work / "frames.txt").write_text("\n".join(out) + "\n")
emu_start = start["10"]
start["10"] += EMU_SECONDS
total = t

texts = {}
for l in narration.read_text(encoding="utf-8").splitlines():
    if "|" in l:
        k, v = l.split("|", 1); texts[k] = v

def ts(x):
    h = int(x // 3600); m = int(x % 3600 // 60); s = x % 60
    return f"{h}:{m:02d}:{s:05.2f}"

order = sorted(start, key=start.get)
ends = {sid: (start[order[k + 1]] if k + 1 < len(order) else total) for k, sid in enumerate(order)}
# 에뮬레이터 화면 직전 장면의 자막이 에뮬레이터 화면까지 이어지지 않게 한다.
for sid in order:
    if start[sid] < emu_start < ends[sid]:
        ends[sid] = emu_start
ass = [
    "[Script Info]", "ScriptType: v4.00+", "PlayResX: 1920", "PlayResY: 1080", "WrapStyle: 0", "",
    "[V4+ Styles]",
    "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
    # 앱 화면은 위쪽 85%에 두고, 아래 띠(162px)에 자막을 쓴다. 화면의 버튼을 가리지 않게 하려는 것이다.
    "Style: Narr,Malgun Gothic,38,&H00EAEFF5,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,150,150,46,1",
    "Style: Tag,Malgun Gothic,20,&H008C7366,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,1,40,40,10,1",
    "Style: Emu,Malgun Gothic,38,&H00EBBD86,&H000000FF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,2,150,150,56,1",
    "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
]
for sid in order:
    if sid in texts and sid not in ("01", "10"):
        ass.append(f"Dialogue: 0,{ts(start[sid] + 0.2)},{ts(ends[sid] - 0.1)},Narr,,0,0,0,,{texts[sid]}")
app_start, app_end = start["03"], emu_start
ass.append(f"Dialogue: 0,{ts(app_start)},{ts(app_end)},Tag,,0,0,0,,앱 실제 화면 · 데모 모드(가상 차량, 빨리 감기 포함)")
ass.append(f"Dialogue: 0,{ts(emu_start)},{ts(emu_start + EMU_SECONDS)},Emu,,0,0,0,,Pleos Connect Emulator 2.1.0에서 실행한 차지냅")
(work / "subs.ass").write_text("\n".join(ass) + "\n", encoding="utf-8")
(work / "starts.txt").write_text("\n".join(f"{sid} {start[sid]:.3f}" for sid in order) + f"\nTOTAL {total:.3f}\n")
PY

TOTAL=$(awk '/^TOTAL/{print $2}' "$WORK/starts.txt")

# 1) 영상
ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i "$WORK/frames.txt" \
  -vf "scale=1632:918,pad=1920:1080:144:0:color=0x060A12,fps=30,format=yuv420p,subtitles=$WORK/subs.ass:fontsdir=$FONTS_DIR" \
  -c:v libx264 -preset slow -crf 20 -t "$TOTAL" "$WORK/video.mp4"

# 2) 내레이션(있으면) — 장면 시작 0.3초 뒤에 각 장면의 문장을 깐다.
if [ -n "$NARR_DIR" ] && ls "$NARR_DIR"/*.wav >/dev/null 2>&1; then
  inputs=(); filters=(); n=0
  while read -r sid t; do
    [ "$sid" = "TOTAL" ] && continue
    wav="$NARR_DIR/$sid.wav"; [ -f "$wav" ] || continue
    inputs+=(-i "$wav"); n=$((n + 1))
    ms=$(python3 -c "print(int(($t + 0.3) * 1000))")
    filters+=("[$n:a]aresample=48000,adelay=delays=${ms}:all=1[a$n]")
  done < "$WORK/starts.txt"
  mix=""; for k in $(seq 1 $n); do mix+="[a$k]"; done
  ffmpeg -hide_banner -loglevel error -y -i "$WORK/video.mp4" "${inputs[@]}" \
    -filter_complex "$(IFS=';'; echo "${filters[*]}");${mix}amix=inputs=$n:normalize=0,apad,atrim=0:$TOTAL,volume=1.4[aout]" \
    -map 0:v -map "[aout]" -c:v copy -c:a aac -b:a 160k -shortest "$OUT"
else
  cp "$WORK/video.mp4" "$OUT"
fi
ffprobe -v error -show_entries format=duration,size -of default=nw=1 "$OUT"

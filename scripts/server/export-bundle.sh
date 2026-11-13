#!/usr/bin/env bash
# 지금 쓰는 PC에서 git에 없는 것을 압축 파일 하나로 묶는다. 서버로 scp 한 뒤 import-bundle.sh로 푼다.
#
# 담는 것 (모두 공개 저장소에 올리면 안 되는 것):
#   secrets/      릴리스 키스토어(.jks)와 keystore.properties
#   submission/   접수양식·제출 메일 초안·시연 영상 (개인정보 포함)
#   chargenap-film/  시연 영상 프로젝트 (node_modules, out 제외)
#   claude/memory/   이 저장소에 대한 Claude Code 자동 메모리
#   claude/rules/    사용자 규칙(~/.claude/rules 등)
#
# 사용: scripts/server/export-bundle.sh [출력 경로]      (기본 ~/chargenap-bundle.tar.gz)
# 환경 변수: FILM_DIR(기본 ~/workspace/chargenap-film), CLAUDE_DIR(기본 $CLAUDE_CONFIG_DIR 또는 ~/.claude),
#            INCLUDE_FILM_OUT=1 이면 렌더 결과(out/)도 담는다
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO="$(pwd)"
OUT="${1:-$HOME/chargenap-bundle.tar.gz}"
FILM_DIR="${FILM_DIR:-$HOME/workspace/chargenap-film}"
CLAUDE_DIR="${CLAUDE_DIR:-${CLAUDE_CONFIG_DIR:-$HOME/.claude}}"
SLUG="$(printf '%s' "$REPO" | sed 's/[^A-Za-z0-9]/-/g')"

umask 077
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
log() { echo "[export] $*"; }

if [ -f keystore.properties ]; then
  STORE="$(grep '^storeFile=' keystore.properties | cut -d= -f2-)"
  mkdir -p "$STAGE/secrets"
  cp keystore.properties "$STAGE/secrets/"
  if [ -f "$STORE" ]; then cp "$STORE" "$STAGE/secrets/"; log "키스토어: $(basename "$STORE")"; else log "경고: 키스토어 $STORE 없음"; fi
else
  log "경고: keystore.properties 없음 — release 서명 정보는 담지 않는다"
fi

[ -d submission ] && { cp -a submission "$STAGE/submission"; log "submission/ $(du -sh submission | cut -f1)"; }

if [ -d "$FILM_DIR" ]; then
  EXCL=(--exclude node_modules)
  [ "${INCLUDE_FILM_OUT:-0}" = "1" ] || EXCL+=(--exclude /out)
  rsync -a "${EXCL[@]}" "$FILM_DIR/" "$STAGE/chargenap-film/"
  log "영상 프로젝트 $(du -sh "$STAGE/chargenap-film" | cut -f1)"
fi

MEM="$CLAUDE_DIR/projects/$SLUG/memory"
if [ -d "$MEM" ]; then
  mkdir -p "$STAGE/claude"
  cp -a "$MEM" "$STAGE/claude/memory"
  log "Claude 메모리 $(ls "$MEM" | wc -l)개 ($MEM)"
else
  log "Claude 메모리 없음 ($MEM). CLAUDE_DIR을 지정해 다시 실행할 수 있다"
fi
for d in "$HOME/.claude/rules" "$CLAUDE_DIR/rules"; do
  [ -d "$d" ] && [ -n "$(ls -A "$d" 2>/dev/null)" ] || continue
  mkdir -p "$STAGE/claude/rules"
  cp -aL "$d/." "$STAGE/claude/rules/"
done

cat > "$STAGE/MANIFEST" <<EOF
created=$(date -Iseconds)
source_home=$HOME
source_repo=$REPO
commit=$(git rev-parse HEAD)
EOF
(cd "$STAGE" && find . -type f | sort) >> "$STAGE/MANIFEST"

tar -czf "$OUT" -C "$STAGE" .
chmod 600 "$OUT"
log "완료: $OUT ($(du -h "$OUT" | cut -f1), sha256 $(sha256sum "$OUT" | cut -c1-16)…)"
cat <<EOF

서버로 옮기기 (로컬 PC에서):
  scp "$OUT" dev@<서버IP>:~/chargenap-bundle.tar.gz
서버에서:
  ~/workspace/chargenap/scripts/server/import-bundle.sh ~/chargenap-bundle.tar.gz
이 파일에는 서명 키와 개인정보가 있다. 옮긴 뒤 양쪽에서 지운다:  rm "$OUT"
EOF

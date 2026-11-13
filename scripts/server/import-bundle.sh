#!/usr/bin/env bash
# export-bundle.sh로 만든 압축 파일을 서버에서 푼다. 개발 계정으로 저장소 안에서 실행한다.
#
#   secrets/          → ~/.chargenap/ (키스토어), 저장소 루트 keystore.properties (storeFile 경로를 이 서버 기준으로 고침)
#   submission/       → 저장소 submission/ (gitignore 대상)
#   chargenap-film/   → ~/workspace/chargenap-film (npm 의존성과 Playwright Chromium 설치)
#   claude/memory/    → <Claude 설정 폴더>/projects/<이 저장소 경로>/memory (있는 파일은 덮어쓰지 않음)
#   claude/rules/     → ~/.claude/rules (있는 파일은 덮어쓰지 않음)
#
# 사용: scripts/server/import-bundle.sh <chargenap-bundle.tar.gz>
# 환경 변수: FILM_DIR(기본 ~/workspace/chargenap-film), CLAUDE_DIR(기본 $CLAUDE_CONFIG_DIR 또는 ~/.claude)
#   ccam으로 여러 계정을 쓴다면 계정을 만든 뒤 그 계정의 설정 폴더를 CLAUDE_DIR로 주고 실행한다.
set -euo pipefail
BUNDLE="$(realpath "${1:?번들 경로}")"
cd "$(dirname "$0")/../.."
REPO="$(pwd)"
FILM_DIR="${FILM_DIR:-$HOME/workspace/chargenap-film}"
CLAUDE_DIR="${CLAUDE_DIR:-${CLAUDE_CONFIG_DIR:-$HOME/.claude}}"
SLUG="$(printf '%s' "$REPO" | sed 's/[^A-Za-z0-9]/-/g')"

umask 077
X="$(mktemp -d)"
trap 'rm -rf "$X"' EXIT
tar -xzf "$BUNDLE" -C "$X"
OLD_HOME="$(grep '^source_home=' "$X/MANIFEST" | cut -d= -f2-)"
log() { echo "[import] $*"; }

if [ -f "$X/secrets/keystore.properties" ]; then
  install -d -m 700 "$HOME/.chargenap"
  JKS="$(find "$X/secrets" -maxdepth 1 -name '*.jks' | head -1)"
  if [ -n "$JKS" ]; then
    install -m 600 "$JKS" "$HOME/.chargenap/$(basename "$JKS")"
    sed "s#^storeFile=.*#storeFile=$HOME/.chargenap/$(basename "$JKS")#" "$X/secrets/keystore.properties" > keystore.properties
  else
    cp "$X/secrets/keystore.properties" keystore.properties
  fi
  chmod 600 keystore.properties
  log "서명 정보: keystore.properties, ~/.chargenap/"
fi

if [ -d "$X/submission" ]; then
  mkdir -p submission
  rsync -a "$X/submission/" submission/
  log "submission/"
fi

if [ -d "$X/chargenap-film" ]; then
  mkdir -p "$FILM_DIR"
  rsync -a "$X/chargenap-film/" "$FILM_DIR/"
  chmod -R go-w "$FILM_DIR"
  log "영상 프로젝트 → $FILM_DIR"
  export NVM_DIR="$HOME/.nvm"
  set +u  # nvm은 set -u에서 깨진다
  # shellcheck disable=SC1091
  [ -s "$NVM_DIR/nvm.sh" ] && source "$NVM_DIR/nvm.sh"
  set -u
  if command -v npm >/dev/null && [ -f "$FILM_DIR/package-lock.json" ]; then
    (cd "$FILM_DIR" && npm ci --silent && npx --yes playwright install chromium >/dev/null) \
      && log "영상 도구: npm 의존성, Chromium" || log "경고: 영상 도구 설치 실패 — $FILM_DIR 에서 npm ci 를 직접 실행하라"
    if sudo -n true 2>/dev/null; then
      (cd "$FILM_DIR" && sudo -n env "PATH=$PATH" npx --yes playwright install-deps chromium >/dev/null) || true
    fi
  fi
fi

if [ -d "$X/claude/memory" ]; then
  DEST="$CLAUDE_DIR/projects/$SLUG/memory"
  mkdir -p "$DEST"
  for f in "$X/claude/memory"/*; do
    [ -e "$DEST/$(basename "$f")" ] && { log "메모리 유지(이미 있음): $(basename "$f")"; continue; }
    # 예전 PC의 홈 경로를 이 서버의 홈 경로로 바꾼다
    if [ -n "$OLD_HOME" ]; then sed "s#$OLD_HOME#$HOME#g" "$f" > "$DEST/$(basename "$f")"; else cp "$f" "$DEST/"; fi
  done
  log "Claude 메모리 → $DEST"
fi

if [ -d "$X/claude/rules" ]; then
  mkdir -p "$HOME/.claude/rules"
  cp -n "$X/claude/rules/"* "$HOME/.claude/rules/" 2>/dev/null || true
  log "사용자 규칙 → ~/.claude/rules"
fi

cat <<EOF

[import] 완료. 번들에는 서명 키와 개인정보가 있으니 지운다:  rm "$BUNDLE"
확인: scripts/package-release.sh 로 서명된 release APK가 만들어지는지 본다.
EOF

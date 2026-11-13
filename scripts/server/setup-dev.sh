#!/usr/bin/env bash
# 개발 계정(dev)으로 저장소 안에서 실행한다. 서버에 차지냅 개발 도구와 Claude Code를 설치한다.
#
#   - JDK 17, Gradle 8.11.1, Android SDK (scripts/bootstrap-toolchain.sh, 홈 디렉터리 설치)
#   - local.properties, 서버 사양에 맞춘 ~/.gradle/gradle.properties (저장소의 5GB 기준 설정을 덮어씀)
#   - 셸 시작 시 scripts/env.sh 적용, Node.js LTS(nvm: 영상 렌더·MCP 서버용), Claude Code
#   - 저장소 커밋 작성자(Moveto)
#
# 사용: scripts/server/setup-dev.sh [--with-emulator] [--with-ccam]
#   --with-emulator  Pleos Connect Emulator 이미지(내려받기 약 4GB, 풀면 약 20GB)와 AVD를 설치한다. /dev/kvm이 있어야 쓸모가 있다
#   --with-ccam      여러 Claude 계정을 오가는 ccam을 설치한다(로컬 PC에서 쓰던 방식)
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO="$(pwd)"
WITH_EMULATOR=0; WITH_CCAM=0
for a in "$@"; do
  case "$a" in
    --with-emulator) WITH_EMULATOR=1 ;;
    --with-ccam) WITH_CCAM=1 ;;
    *) sed -n '2,13p' "$0"; exit 2 ;;
  esac
done
log() { echo "[setup-dev] $*"; }

log "툴체인(JDK 17, Gradle, Android SDK)"
scripts/bootstrap-toolchain.sh
# shellcheck source=../env.sh
source scripts/env.sh
echo "sdk.dir=$ANDROID_HOME" > local.properties

log "Gradle 설정을 서버 사양에 맞춤 (~/.gradle/gradle.properties가 저장소 gradle.properties보다 우선)"
MEM_GB="$(free -g | awk '/Mem:/{print $2}')"
CPUS="$(nproc)"
XMX_GB=$(( MEM_GB / 4 )); [ "$XMX_GB" -lt 2 ] && XMX_GB=2; [ "$XMX_GB" -gt 8 ] && XMX_GB=8
WORKERS=$(( CPUS > 16 ? 16 : CPUS ))
GP="$HOME/.gradle/gradle.properties"
mkdir -p "$HOME/.gradle"
touch "$GP"
sed -i '/^# >>> chargenap server >>>$/,/^# <<< chargenap server <<<$/d' "$GP"
cat >> "$GP" <<EOF
# >>> chargenap server >>>
# setup-dev.sh가 쓴 값 (메모리 ${MEM_GB}GB, CPU ${CPUS}코어). 다시 실행하면 갱신된다.
org.gradle.jvmargs=-Xmx${XMX_GB}g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
org.gradle.workers.max=${WORKERS}
# <<< chargenap server <<<
EOF

log "셸 시작 시 env.sh 적용"
for rc in "$HOME/.bashrc" "$HOME/.zshrc"; do
  [ -f "$rc" ] || continue
  grep -q "chargenap/scripts/env.sh" "$rc" || echo "[ -f \"$REPO/scripts/env.sh\" ] && source \"$REPO/scripts/env.sh\"" >> "$rc"
  grep -q 'HOME/.local/bin' "$rc" || echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$rc"
done

log "Node.js LTS (nvm)"
export NVM_DIR="$HOME/.nvm"
if [ ! -s "$NVM_DIR/nvm.sh" ]; then
  curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash >/dev/null
fi
# nvm은 set -u에서 깨지므로 잠시 끈다
set +u
# shellcheck disable=SC1091
source "$NVM_DIR/nvm.sh"
nvm install --lts >/dev/null
nvm alias default 'lts/*' >/dev/null
set -u
node --version

log "Claude Code"
export PATH="$HOME/.local/bin:$PATH"
if ! command -v claude >/dev/null; then
  curl -fsSL https://claude.ai/install.sh | bash
fi
claude --version || true
if [ "$WITH_CCAM" = "1" ] && ! command -v ccam >/dev/null; then
  log "ccam"
  curl -fsSL https://ccam.dev/install.sh | sh
fi

log "저장소 커밋 작성자 (개인 계정 정보가 공모전 링크에 드러나지 않게)"
git config user.name "Moveto"
git config user.email "moveto-team@users.noreply.github.com"

if [ "$WITH_EMULATOR" = "1" ]; then
  if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
    log "Pleos Connect Emulator"
    scripts/install-pleos-emulator.sh 4096
  else
    log "건너뜀: /dev/kvm을 쓸 수 없다. kvm 그룹 반영을 위해 다시 로그인했는지, 인스턴스가 KVM을 주는지 확인하라 (docs/server-setup.md)"
  fi
fi

cat <<EOF

[setup-dev] 완료. 새 셸을 열거나 'source ~/.bashrc' 한 뒤:
  1) 로컬 PC에서 만든 번들을 옮겨 풀기:  scripts/server/import-bundle.sh ~/chargenap-bundle.tar.gz
  2) Claude Code 로그인:                 claude   (화면의 주소를 로컬 브라우저에서 열고 코드를 붙여 넣는다)
  3) 플러그인:                           scripts/server/install-claude-plugins.sh
  4) 확인:                               ./gradlew testDebugUnitTest
  5) 에뮬레이터(KVM이 있을 때):          scripts/server/emu.sh up
EOF

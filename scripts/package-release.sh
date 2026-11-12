#!/usr/bin/env bash
# release APK를 빌드해 공모전 규정 이름으로 apk/에 복사한다.
#   apk/<팀명>_ChargeNap_release.apk
# 사용: scripts/package-release.sh [팀명]     (생략하면 gradle.properties의 chargenap.teamName)
# 필요: 저장소 루트의 keystore.properties (커밋 금지). 없으면 아래 안내를 출력하고 멈춘다.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

TEAM="${1:-$(grep '^chargenap.teamName=' gradle.properties | cut -d= -f2)}"
[ -n "$TEAM" ] || { echo "팀명이 비어 있습니다" >&2; exit 1; }

if [ ! -f keystore.properties ]; then
  cat >&2 <<'EOF'
keystore.properties가 없습니다. 키스토어를 만들고 아래 형식으로 저장하세요(둘 다 커밋 금지).

  keytool -genkeypair -v -keystore ~/.chargenap/chargenap-release.jks -alias chargenap \
          -keyalg RSA -keysize 2048 -validity 10000

  # keystore.properties
  storeFile=/절대/경로/chargenap-release.jks
  storePassword=...
  keyAlias=chargenap
  keyPassword=...
EOF
  exit 1
fi

./gradlew assembleRelease --console=plain -q
SRC="app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "서명된 release APK가 없습니다: $SRC" >&2; exit 1; }

mkdir -p apk
rm -f apk/*_ChargeNap_release.apk
DEST="apk/${TEAM}_ChargeNap_release.apk"
cp "$SRC" "$DEST"

"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$DEST" | head -2
ls -la "$DEST"

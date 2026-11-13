#!/usr/bin/env bash
# 새 Vultr 서버(Ubuntu 24.04/22.04 x64)를 차지냅 개발 서버로 준비한다. root로 한 번 실행한다.
#
#   - 개발 계정 생성(기본 dev), root의 SSH 공개키 복사, sudo 권한
#   - 빌드·에뮬레이터·영상 도구가 쓰는 패키지, 한글 글꼴·로캘, 시간대(Asia/Seoul)
#   - KVM 권한(에뮬레이터 가속), 스왑, 방화벽(SSH만 허용), SSH 비밀번호 로그인 끄기(공개키가 있을 때만)
#
# 사용 (서버에서, root):
#   curl -fsSL https://raw.githubusercontent.com/moveto-team/chargenap/main/scripts/server/setup-root.sh -o setup-root.sh
#   bash setup-root.sh [계정명]
# 환경 변수:
#   DEV_SUDO_NOPASSWD=0   sudo에 비밀번호를 요구한다(기본 1: 비밀번호 없이 sudo. Claude Code가 패키지 설치를 할 수 있게)
#   SWAP_GB=8             스왑 크기. 0이면 만들지 않는다
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "root로 실행하세요" >&2; exit 1; }
DEV_USER="${1:-dev}"
DEV_SUDO_NOPASSWD="${DEV_SUDO_NOPASSWD:-1}"
SWAP_GB="${SWAP_GB:-8}"
export DEBIAN_FRONTEND=noninteractive

log() { echo "[setup-root] $*"; }

log "패키지 설치"
apt-get update -qq
apt-get install -y -qq \
  git gh curl wget unzip zip jq tmux htop rsync ca-certificates gnupg build-essential \
  python3 python3-venv python3-pip ffmpeg \
  fonts-noto-cjk fonts-nanum locales tzdata acl ufw cpu-checker \
  libpulse0 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 libxrandr2 \
  libgl1 libegl1 libgbm1 libdrm2 libxkbfile1 libsm6 libice6 libdbus-1-3 libfontconfig1 \
  libxkbcommon0 libxkbcommon-x11-0 libxcb-cursor0 >/dev/null
# 패키지 이름이 배포판마다 다르다(24.04: libasound2t64, 22.04: libasound2)
apt-get install -y -qq libasound2t64 >/dev/null 2>&1 || apt-get install -y -qq libasound2 >/dev/null

log "로캘·시간대"
locale-gen ko_KR.UTF-8 en_US.UTF-8 >/dev/null
timedatectl set-timezone Asia/Seoul 2>/dev/null || ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime

log "개발 계정 $DEV_USER"
if ! id "$DEV_USER" >/dev/null 2>&1; then
  adduser --disabled-password --gecos "" "$DEV_USER" >/dev/null
fi
usermod -aG sudo "$DEV_USER"
getent group kvm >/dev/null || groupadd -r kvm
usermod -aG kvm "$DEV_USER"
if [ "$DEV_SUDO_NOPASSWD" = "1" ]; then
  echo "$DEV_USER ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/90-$DEV_USER"
  chmod 440 "/etc/sudoers.d/90-$DEV_USER"
fi
if [ -s /root/.ssh/authorized_keys ]; then
  install -d -m 700 -o "$DEV_USER" -g "$DEV_USER" "/home/$DEV_USER/.ssh"
  install -m 600 -o "$DEV_USER" -g "$DEV_USER" /root/.ssh/authorized_keys "/home/$DEV_USER/.ssh/authorized_keys"
fi

log "KVM 확인"
if [ -e /dev/kvm ]; then
  # 재부팅 뒤에도 kvm 그룹이 /dev/kvm을 쓸 수 있게 한다
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0660"' > /etc/udev/rules.d/65-kvm.rules
  udevadm control --reload-rules && udevadm trigger --name-match=kvm || true
  chgrp kvm /dev/kvm && chmod 660 /dev/kvm
  kvm-ok || true
  KVM_STATUS="사용 가능"
else
  KVM_STATUS="없음 — 이 인스턴스에서는 에뮬레이터를 가속할 수 없다. docs/server-setup.md의 'KVM이 없을 때'를 보라"
fi

if [ "$SWAP_GB" -gt 0 ] && ! swapon --show | grep -q .; then
  log "스왑 ${SWAP_GB}GB"
  fallocate -l "${SWAP_GB}G" /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

log "방화벽: SSH만 허용 (Virtual Console 8088, adb 5555는 SSH 터널로만 쓴다)"
ufw allow OpenSSH >/dev/null
ufw --force enable >/dev/null

if [ -s "/home/$DEV_USER/.ssh/authorized_keys" ]; then
  log "SSH 비밀번호 로그인 끄기 (공개키 로그인만)"
  # sshd는 처음 읽은 값을 쓴다. cloud-init이 만드는 50-cloud-init.conf보다 먼저 읽히게 01-로 둔다
  SSHD_DROPIN=/etc/ssh/sshd_config.d/01-chargenap.conf
  cat > "$SSHD_DROPIN" <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
EOF
  if sshd -t; then
    systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true
  else
    rm -f "$SSHD_DROPIN"
    log "경고: sshd 설정 검사 실패 — SSH 설정을 바꾸지 않았다"
  fi
else
  log "주의: SSH 공개키가 없어 비밀번호 로그인을 그대로 둔다. 공개키를 등록한 뒤 이 스크립트를 다시 실행하라"
fi

cat <<EOF

[setup-root] 완료
  CPU: $(nproc)코어, 메모리: $(free -g | awk '/Mem:/{print $2}')GB, 디스크 여유: $(df -h / | awk 'NR==2{print $4}')
  KVM: $KVM_STATUS
다음 (로컬 PC에서):
  ssh $DEV_USER@<서버IP>
  git clone https://github.com/moveto-team/chargenap.git ~/workspace/chargenap
  cd ~/workspace/chargenap && scripts/server/setup-dev.sh
EOF

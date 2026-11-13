#!/usr/bin/env bash
# 이 프로젝트에서 쓰던 Claude Code 플러그인을 서버에 설치한다. Claude Code에 로그인한 뒤 실행한다.
# (예전 PC에서 켜 둔 플러그인 중 이 프로젝트와 관계없는 것 — SEO, Instagram, Expo, Cloudflare 등 — 은 뺐다)
#
# 사용: scripts/server/install-claude-plugins.sh
#   실패한 것은 끝에 모아 보여 준다. Claude Code 안에서 /plugin 으로 찾아 설치해도 된다.
set -uo pipefail
export PATH="$HOME/.local/bin:$PATH"
command -v claude >/dev/null || { echo "claude가 없다. scripts/server/setup-dev.sh를 먼저 실행하라" >&2; exit 1; }

PLUGINS=(
  superpowers@anthropic-plugin-directory          # 브레인스토밍·계획·TDD·검증 절차
  context7@anthropic-plugin-directory             # 라이브러리 최신 문서 (Node.js 필요)
  commit-commands@anthropic-plugin-directory      # /commit
  code-review-anthropics@anthropic-plugin-directory
  pr-review-toolkit@anthropic-plugin-directory    # 독립 코드 리뷰 에이전트
  feature-dev@anthropic-plugin-directory
  code-simplifier@anthropic-plugin-directory
  claude-md-management@anthropic-plugin-directory # CLAUDE.md 정리
  claude-code-setup@anthropic-plugin-directory
  security-guidance@anthropic-plugin-directory
  motion-reel@anthropic-plugin-directory          # 시연 영상(chargenap-film) 다시 만들 때
)

failed=()
for p in "${PLUGINS[@]}"; do
  if claude plugin install "$p" >/dev/null 2>&1; then echo "설치: $p"; else failed+=("$p"); fi
done

if command -v ccam >/dev/null; then
  claude plugin install ccam --marketplace jhyunwoo/ccam >/dev/null 2>&1 && echo "설치: ccam" || failed+=("ccam (jhyunwoo/ccam)")
fi

if [ "${#failed[@]}" -gt 0 ]; then
  echo
  echo "설치하지 못한 플러그인 (Claude Code 안에서 /plugin 으로 이름을 검색해 설치):"
  printf '  - %s\n' "${failed[@]}"
fi

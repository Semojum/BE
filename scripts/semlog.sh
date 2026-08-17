#!/usr/bin/env bash
# 운영 백엔드 로그를 색칠해서 실시간으로 본다.
# 저장되는 로그는 무색 플레인 텍스트 그대로 두고, 색은 보는 순간(이 스크립트)에만 입힌다 —
# ANSI 코드가 파일에 박히면 grep·수집기·복붙이 깨지기 때문 (현업 관행: 저장은 무색, 색칠은 뷰어).
#
# 사용법:
#   ./scripts/semlog.sh            # 실시간 tail (기본 최근 200줄부터)
#   ./scripts/semlog.sh 1000       # 최근 1000줄부터
#   ./scripts/semlog.sh 500 job_260810210512   # 최근 500줄 중 해당 작업만
#
# 색: ERROR=빨강, WARN=노랑, REQ 액세스 로그=시안, 스케줄러 상태=보라
#
# alias 등록(권장): ~/.zshrc 에
#   alias semlog='~/semojum/backend/scripts/semlog.sh'

set -euo pipefail

TAIL="${1:-200}"
FILTER="${2:-}"

# blue/green 두 색 모두 지정 — 평소엔 한 색만 떠 있어 그쪽 로그만 흐르고, 전환 중엔 양쪽이 섞여 보인다
STREAM="ssh semojum-aws 'cd ~/semojum && docker compose --profile blue --profile green logs -f --no-log-prefix --tail ${TAIL} backend-blue backend-green'"

colorize() {
  awk '
    /ERROR/               { printf "\033[31m%s\033[0m\n", $0; next }   # 빨강
    / WARN /              { printf "\033[33m%s\033[0m\n", $0; next }   # 노랑
    / REQ /               { printf "\033[36m%s\033[0m\n", $0; next }   # 시안
    /스케줄러 상태/         { printf "\033[35m%s\033[0m\n", $0; next }   # 보라
    { print }
  '
}

if [ -n "$FILTER" ]; then
  eval "$STREAM" | grep --line-buffered "$FILTER" | colorize
else
  eval "$STREAM" | colorize
fi

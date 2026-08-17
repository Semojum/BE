#!/usr/bin/env bash
# 블루그린 무중단 배포 — CI(dev_deploy.yml)가 EC2에서 실행한다.
#
# 흐름:
#   1. 활성 색 감지 (blue/green 중 떠 있는 쪽)
#   2. 새 이미지 pull → 비활성 색으로 기동
#   3. 헬스 게이트: /api/health 200까지 대기(최대 120초) — 실패하면 새 색만 내리고 종료(구버전 무사)
#   4. Envoy가 새 색을 healthy로 편입할 시간을 준 뒤 구 색 graceful 정지
#   5. 구 이미지 정리
#
# 롤백: 방금 내려간 색을 다시 켜면 된다 (구 이미지가 로컬에 남아 있음)
#   docker compose --profile blue up -d backend-blue && docker compose --profile green stop backend-green
set -euo pipefail
cd /home/ubuntu/semojum

log() { echo "[deploy] $(date '+%H:%M:%S') $*"; }

# ── 1. 활성 색 감지 ─────────────────────────────────────────────
if docker ps --format '{{.Names}}' | grep -q 'backend-blue'; then
  ACTIVE=blue; IDLE=green; IDLE_PORT=8082
elif docker ps --format '{{.Names}}' | grep -q 'backend-green'; then
  ACTIVE=green; IDLE=blue; IDLE_PORT=8081
else
  ACTIVE=none; IDLE=blue; IDLE_PORT=8081   # 최초 전환(구 단일 backend 체제)
fi
log "활성=${ACTIVE} → 새 버전은 ${IDLE}로 기동"

# ── 2. 새 이미지로 비활성 색 기동 ────────────────────────────────
docker pull zxhwan/semojum-backend:latest
docker compose up -d redis
docker compose --profile "$IDLE" up -d "backend-$IDLE"

# ── 3. 헬스 게이트 (최대 120초) ──────────────────────────────────
HEALTHY=0
for i in $(seq 1 60); do
  if curl -sf "http://localhost:${IDLE_PORT}/api/health" >/dev/null 2>&1; then
    HEALTHY=1; break
  fi
  sleep 2
done
if [ "$HEALTHY" != "1" ]; then
  log "FAIL: ${IDLE} 헬스 실패 — 새 색 중지, ${ACTIVE}가 계속 서비스"
  docker compose --profile "$IDLE" logs --tail 50 "backend-$IDLE" || true
  docker compose --profile "$IDLE" stop "backend-$IDLE"
  exit 1
fi
log "OK: ${IDLE} 헬스 통과"

# ── 4. 전환 ─────────────────────────────────────────────────────
if [ "$ACTIVE" = "none" ]; then
  # 최초 전환: 새 envoy.yaml 반영(정적 설정이라 재기동 필요) 후 구 단일 backend 컨테이너 제거.
  # envoy 재기동 순간 약 1~2초 연결 단절 — 이 1회에 한해 감수 (이후 배포는 envoy 무재기동)
  log "최초 전환 — envoy 재기동(새 설정 반영)"
  docker compose up -d --force-recreate envoy
  sleep 12   # envoy 기동 + 헬스체크 2회(3s×2)로 새 색 편입 대기
  if docker ps -a --format '{{.Names}}' | grep -q '^semojum-backend-1$'; then
    log "구 단일 backend 컨테이너 정지·제거"
    docker stop semojum-backend-1 || true
    docker rm semojum-backend-1 || true
  fi
else
  # 평상시 전환: envoy는 손대지 않는다 — 헬스체크가 알아서 새 색 편입
  # 액티브 헬스체크 interval 3s × healthy_threshold 2 → 최대 ~6s + 여유
  sleep 10
  docker compose --profile "$ACTIVE" stop "backend-$ACTIVE"
  log "구 색(${ACTIVE}) graceful 정지 완료"
fi

# ── 5. 정리 ─────────────────────────────────────────────────────
docker image prune -f >/dev/null
log "배포 완료: ${IDLE} 활성"

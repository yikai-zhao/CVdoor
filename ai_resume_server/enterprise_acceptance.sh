#!/usr/bin/env bash
set -euo pipefail

# Enterprise acceptance runner (local/EC2)
# Supports non-interactive env loading from local files.

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

API_BASE_URL="http://127.0.0.1:8010"

start_backend() {
  pkill -f "uvicorn main:app --host 127.0.0.1 --port 8010" >/dev/null 2>&1 || true
  nohup python3 -m uvicorn main:app --host 127.0.0.1 --port 8010 >/tmp/cvdoor_acceptance_backend.log 2>&1 &
}

wait_backend_health() {
  local tries="${1:-90}"
  for _ in $(seq 1 "$tries"); do
    if curl -fsS --max-time 3 "${API_BASE_URL}/healthz" >/tmp/cvdoor_acceptance_health.json 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

ensure_backend_alive() {
  if curl -fsS --max-time 3 "${API_BASE_URL}/healthz" >/dev/null 2>&1; then
    return 0
  fi
  echo "[WARN] Backend is not responding; restarting once..."
  start_backend
  wait_backend_health 30
}

load_env_file() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" != *"="* ]] && continue
    local key="${line%%=*}"
    local val="${line#*=}"
    key="$(echo "$key" | xargs)"
    [[ -z "$key" ]] && continue
    # Keep existing exported values as highest priority.
    if [[ -z "${!key:-}" ]]; then
      # Trim surrounding quotes if present.
      if [[ "$val" =~ ^\".*\"$ ]]; then
        val="${val:1:${#val}-2}"
      elif [[ "$val" =~ ^\'.*\'$ ]]; then
        val="${val:1:${#val}-2}"
      fi
      export "$key=$val"
    fi
  done < "$file"
}

# Auto-load common env files so the operator doesn't need interactive input.
load_env_file "/workspaces/CVdoor/.env.recovered"
load_env_file "$ROOT_DIR/.env"

# Safe default for this repository's Play package.
export GOOGLE_PLAY_PACKAGE_NAME="${GOOGLE_PLAY_PACKAGE_NAME:-com.synmodel.cvdoor}"

require_env() {
  local key="$1"
  if [[ -z "${!key:-}" ]]; then
    echo "[FAIL] missing env: $key"
    exit 1
  fi
}

echo "[1/8] Validate required env vars"
require_env OPENAI_API_KEY
require_env SESSION_TOKEN_SECRET
require_env SERVER_API_KEY
require_env DATABASE_URL
require_env GOOGLE_PLAY_PACKAGE_NAME
require_env STRIPE_SECRET_KEY
require_env STRIPE_WEBHOOK_SECRET
require_env STRIPE_PRICE_MONTHLY
require_env STRIPE_PRICE_ANNUAL
if [[ -z "${GOOGLE_PLAY_SERVICE_ACCOUNT_JSON:-}" && -z "${GOOGLE_APPLICATION_CREDENTIALS:-}" ]]; then
  echo "[WARN] GOOGLE_PLAY_SERVICE_ACCOUNT_JSON/GOOGLE_APPLICATION_CREDENTIALS not set; relying on ADC if available."
fi

if [[ "${GOOGLE_PLAY_PACKAGE_NAME}" != "com.synmodel.cvdoor" ]]; then
  echo "[FAIL] GOOGLE_PLAY_PACKAGE_NAME must be com.synmodel.cvdoor"
  exit 1
fi

echo "[2/8] Run production preflight"
bash "$ROOT_DIR/preflight_prod.sh"

echo "[3/8] Python syntax check"
python3 -m py_compile "$ROOT_DIR/main.py"

echo "[4/8] Ensure backend dependencies"
if ! python3 -m pip install --break-system-packages -r "$ROOT_DIR/requirements.txt" >/tmp/cvdoor_acceptance_pip.log 2>&1; then
  if ! python3 -m pip install --user -r "$ROOT_DIR/requirements.txt" >>/tmp/cvdoor_acceptance_pip.log 2>&1; then
    if ! python3 -m pip install -r "$ROOT_DIR/requirements.txt" >>/tmp/cvdoor_acceptance_pip.log 2>&1; then
      echo "[FAIL] pip install failed. See /tmp/cvdoor_acceptance_pip.log"
      exit 1
    fi
  fi
fi

echo "[5/8] Check DB reachability and start backend on :8010"
python3 - <<'PY' "${DATABASE_URL}"
import socket
import sys
from urllib.parse import urlparse

url = sys.argv[1]
u = urlparse(url)
host = u.hostname
port = u.port or 5432
if not host:
    print("[FAIL] DATABASE_URL parse failed: missing hostname")
    raise SystemExit(1)
try:
    with socket.create_connection((host, port), timeout=5):
        print(f"[PASS] DB TCP reachable: {host}:{port}")
except Exception as e:
    print(f"[FAIL] DB TCP unreachable: {host}:{port} ({e})")
    print("      Check VPC routing, RDS SG inbound 5432, and EC2 SG egress rules.")
    raise SystemExit(1)
PY

start_backend
if ! wait_backend_health 90; then
  echo "[FAIL] health check failed"
  echo "[INFO] showing backend startup log tail:"
  tail -n 120 /tmp/cvdoor_acceptance_backend.log || true
  exit 1
fi

echo "[6/8] Stripe checkout API smoke (expects a checkout URL)"
checkout_resp="$(curl -sS --max-time 30 -X POST "${API_BASE_URL}/v1/billing/stripe/checkout" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${SERVER_API_KEY}" \
  -d '{"plan":"monthly","user_id":"usr-acceptance"}')"

python3 - <<'PY' "$checkout_resp"
import json, sys
raw = sys.argv[1]
obj = json.loads(raw)
url = (obj.get("url") or "").strip()
if not url.startswith("https://"):
    print("[FAIL] Stripe checkout did not return URL:", raw)
    raise SystemExit(1)
print("[PASS] Stripe checkout URL generated")
PY

ensure_backend_alive || {
  echo "[FAIL] backend did not recover after Stripe checkout"
  tail -n 120 /tmp/cvdoor_acceptance_backend.log || true
  exit 1
}

echo "[7/8] Entitlement read API smoke"
ent_resp="$(curl -sS --max-time 30 -H "X-API-Key: ${SERVER_API_KEY}" "${API_BASE_URL}/v1/entitlements/usr-acceptance")"
python3 - <<'PY' "$ent_resp"
import json, sys
obj = json.loads(sys.argv[1])
required = ["plan", "is_active", "free_remaining"]
missing = [k for k in required if k not in obj]
if missing:
    print("[FAIL] entitlement response missing fields", missing)
    raise SystemExit(1)
print("[PASS] Entitlement API shape OK")
PY

echo "[8/8] Google Play verify"
PLAY_VERIFY_USER_ID="${PLAY_VERIFY_USER_ID:-usr-acceptance}"
PLAY_VERIFY_PRODUCT_ID="${PLAY_VERIFY_PRODUCT_ID:-cvdoor_pro_monthly}"
if [[ -n "${PLAY_VERIFY_TOKEN:-}" && -n "${PLAY_VERIFY_ORDER_ID:-}" ]]; then
  verify_resp="$(curl -sS --max-time 30 -X POST "${API_BASE_URL}/v1/billing/verify" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: ${SERVER_API_KEY}" \
    -d "{\"user_id\":\"${PLAY_VERIFY_USER_ID}\",\"product_id\":\"${PLAY_VERIFY_PRODUCT_ID}\",\"purchase_token\":\"${PLAY_VERIFY_TOKEN}\",\"order_id\":\"${PLAY_VERIFY_ORDER_ID}\"}")"
  python3 - <<'PY' "$verify_resp"
import json, sys
obj = json.loads(sys.argv[1])
if not obj.get("ok"):
    print("[FAIL] Play verify failed:", obj)
    raise SystemExit(1)
print("[PASS] Play verify succeeded")
PY
else
  echo "[TODO] Set PLAY_VERIFY_TOKEN and PLAY_VERIFY_ORDER_ID env vars to auto-run real Play verification."
  echo "       Current run skipped real token verification."
fi

echo "[DONE] Automated acceptance completed. Backend log: /tmp/cvdoor_acceptance_backend.log"

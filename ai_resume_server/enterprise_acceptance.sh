#!/usr/bin/env bash
set -euo pipefail

# Enterprise acceptance runner (local/EC2)
# Supports non-interactive env loading from local files.

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

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
python3 -m pip install --break-system-packages -r "$ROOT_DIR/requirements.txt" >/tmp/cvdoor_acceptance_pip.log 2>&1 || {
  echo "[FAIL] pip install failed. See /tmp/cvdoor_acceptance_pip.log"
  exit 1
}

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

pkill -f "uvicorn main:app --host 127.0.0.1 --port 8010" >/dev/null 2>&1 || true
nohup python3 -m uvicorn main:app --host 127.0.0.1 --port 8010 >/tmp/cvdoor_acceptance_backend.log 2>&1 &

for _ in $(seq 1 90); do
  if curl -fsS --max-time 3 "http://127.0.0.1:8010/healthz" >/tmp/cvdoor_acceptance_health.json 2>/dev/null; then
    break
  fi
  sleep 1
done

if ! test -s /tmp/cvdoor_acceptance_health.json; then
  echo "[FAIL] health check failed"
  echo "[INFO] showing backend startup log tail:"
  tail -n 120 /tmp/cvdoor_acceptance_backend.log || true
  exit 1
fi

echo "[6/8] Stripe checkout API smoke (expects a checkout URL)"
checkout_resp="$(curl -sS --max-time 30 -X POST "http://127.0.0.1:8010/v1/billing/stripe/checkout" \
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

echo "[7/8] Entitlement read API smoke"
ent_resp="$(curl -sS --max-time 30 -H "X-API-Key: ${SERVER_API_KEY}" "http://127.0.0.1:8010/v1/entitlements/usr-acceptance")"
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

echo "[8/8] Manual Play verification step required"
echo "[TODO] Use an actual Play test purchase token and call /v1/billing/verify."
echo "       Example command (replace TOKEN/ORDER):"
echo "curl -sS -X POST http://127.0.0.1:8010/v1/billing/verify \\" 
echo "  -H 'Content-Type: application/json' -H \"X-API-Key: ${SERVER_API_KEY}\" \\" 
echo "  -d '{\"user_id\":\"usr-acceptance\",\"product_id\":\"cvdoor_pro_monthly\",\"purchase_token\":\"TOKEN\",\"order_id\":\"ORDER\"}'"

echo "[DONE] Automated acceptance completed. Backend log: /tmp/cvdoor_acceptance_backend.log"

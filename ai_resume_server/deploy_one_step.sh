#!/usr/bin/env bash
set -euo pipefail

# One-step deploy for CVdoor AI backend
# Usage:
#   OPENAI_API_KEY='sk-...' bash deploy_one_step.sh
# Optional env:
#   OPENAI_MODEL=gpt-4o-mini PORT=8000 HOST=0.0.0.0

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "ERROR: OPENAI_API_KEY is required"
  exit 1
fi

OPENAI_MODEL="${OPENAI_MODEL:-gpt-4o-mini}"
PORT="${PORT:-8000}"
HOST="${HOST:-0.0.0.0}"
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$ROOT_DIR"

echo "[1/6] Install dependencies"
/usr/bin/python3 -m pip install --break-system-packages -r requirements.txt >/tmp/cvdoor_pip.log 2>&1 || {
  echo "Pip install failed. See /tmp/cvdoor_pip.log"
  exit 1
}

echo "[2/6] Stop old service"
pkill -f "uvicorn main:app" >/dev/null 2>&1 || true

echo "[3/6] Start service"
export OPENAI_API_KEY
export OPENAI_MODEL
nohup /usr/bin/python3 -m uvicorn main:app --host "$HOST" --port "$PORT" >/tmp/cvdoor_backend.log 2>&1 &

sleep 2

echo "[4/6] Health check"
HEALTH_JSON="$(curl -sS --max-time 20 "http://127.0.0.1:${PORT}/healthz")"
echo "$HEALTH_JSON"

echo "[5/6] Functional check: /v1/optimize must return non-empty cover_letter"
RESP="$(curl -sS --max-time 120 -X POST "http://127.0.0.1:${PORT}/v1/optimize" \
  -H 'Content-Type: application/json' \
  -d '{"resume_text":"John Doe\nMarketing Assistant\nManaged social media channels, produced campaign reports, and coordinated content launches.","jd_text":"We are hiring a Marketing Assistant to support social media campaigns, analytics reporting, and cross-team coordination.","user_id":null}')"

/usr/bin/python3 - <<'PY' "$RESP"
import json, sys
raw = sys.argv[1]
obj = json.loads(raw)
cl = (obj.get("cover_letter") or "").strip()
if not cl:
    print("ERROR: cover_letter is empty")
    sys.exit(2)
print("OK: cover_letter_len=", len(cl))
print("OK: after_total=", obj.get("after_total"))
PY

echo "[6/6] Functional check: /v1/generate-cover-letter"
RESP2="$(curl -sS --max-time 120 -X POST "http://127.0.0.1:${PORT}/v1/generate-cover-letter" \
  -H 'Content-Type: application/json' \
  -d '{"resume_text":"John Doe\nMarketing Assistant\nManaged social media channels, produced campaign reports, and coordinated content launches.","jd_text":"We are hiring a Marketing Assistant to support social media campaigns, analytics reporting, and cross-team coordination.","user_id":null}')"

/usr/bin/python3 - <<'PY' "$RESP2"
import json, sys
raw = sys.argv[1]
obj = json.loads(raw)
cl = (obj.get("cover_letter") or "").strip()
if not cl:
    print("ERROR: generate-cover-letter returned empty")
    sys.exit(3)
print("OK: regen_cover_letter_len=", len(cl))
PY

echo "DEPLOY SUCCESS"
echo "Service URL: http://127.0.0.1:${PORT}"
echo "Log file: /tmp/cvdoor_backend.log"
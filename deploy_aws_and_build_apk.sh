#!/usr/bin/env bash
set -euo pipefail

# Full auto deploy to AWS + optional APK rebuild.
# Run from repo root:
#   AWS_HOST=ec2-x-x-x-x.compute.amazonaws.com \
#   AWS_USER=ubuntu \
#   AWS_SSH_KEY=~/.ssh/your-key.pem \
#   OPENAI_API_KEY='sk-...' \
#   bash ./deploy_aws_and_build_apk.sh
#
# Optional:
#   AWS_SSH_PORT=22
#   REMOTE_DIR=/opt/cvdoor/ai_resume_server
#   BACKEND_PORT=8000
#   OPENAI_MODEL=gpt-4o-mini
#   REBUILD_APK=true
#   JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.10-ms
#   ANDROID_SDK_ROOT=/opt/android-sdk

AWS_HOST="${AWS_HOST:-}"
AWS_USER="${AWS_USER:-ubuntu}"
AWS_SSH_KEY="${AWS_SSH_KEY:-}"
AWS_SSH_PORT="${AWS_SSH_PORT:-22}"
REMOTE_DIR="${REMOTE_DIR:-/opt/cvdoor/ai_resume_server}"
BACKEND_PORT="${BACKEND_PORT:-8000}"
OPENAI_MODEL="${OPENAI_MODEL:-gpt-4o-mini}"
OPENAI_API_KEY="${OPENAI_API_KEY:-}"
REBUILD_APK="${REBUILD_APK:-true}"

if [[ -z "$AWS_HOST" || -z "$AWS_SSH_KEY" || -z "$OPENAI_API_KEY" ]]; then
  echo "ERROR: AWS_HOST, AWS_SSH_KEY, OPENAI_API_KEY are required"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

if [[ ! -f "$AWS_SSH_KEY" ]]; then
  echo "ERROR: AWS_SSH_KEY file not found: $AWS_SSH_KEY"
  exit 1
fi

SSH_BASE=(ssh -i "$AWS_SSH_KEY" -p "$AWS_SSH_PORT" -o StrictHostKeyChecking=no "$AWS_USER@$AWS_HOST")
SCP_BASE=(scp -i "$AWS_SSH_KEY" -P "$AWS_SSH_PORT" -o StrictHostKeyChecking=no)

echo "[1/7] Prepare remote directory"
"${SSH_BASE[@]}" "mkdir -p '$REMOTE_DIR'"

echo "[2/7] Upload backend files"
# Use tar+scp for compatibility
TMP_TAR="/tmp/cvdoor_ai_server.tar.gz"
tar -czf "$TMP_TAR" \
  --exclude='__pycache__' \
  --exclude='.venv' \
  --exclude='cvdoor.db' \
  --exclude='*.pyc' \
  -C "$ROOT_DIR" ai_resume_server
"${SCP_BASE[@]}" "$TMP_TAR" "$AWS_USER@$AWS_HOST:/tmp/cvdoor_ai_server.tar.gz"
rm -f "$TMP_TAR"

echo "[3/7] Deploy and start backend on AWS"
KEY_B64="$(printf '%s' "$OPENAI_API_KEY" | base64 -w0)"
MODEL_B64="$(printf '%s' "$OPENAI_MODEL" | base64 -w0)"

"${SSH_BASE[@]}" "KEY_B64='$KEY_B64' MODEL_B64='$MODEL_B64' REMOTE_DIR='$REMOTE_DIR' BACKEND_PORT='$BACKEND_PORT' bash -s" <<'REMOTE'
set -euo pipefail
mkdir -p "$REMOTE_DIR"
rm -rf "$REMOTE_DIR"/*
tar -xzf /tmp/cvdoor_ai_server.tar.gz -C /tmp
cp -r /tmp/ai_resume_server/* "$REMOTE_DIR"/

cd "$REMOTE_DIR"
if ! /usr/bin/python3 -m pip install --break-system-packages -r requirements.txt >/tmp/cvdoor_pip.log 2>&1; then
  if ! /usr/bin/python3 -m pip install --user -r requirements.txt >/tmp/cvdoor_pip.log 2>&1; then
    echo "PIP_FAILED"
    cat /tmp/cvdoor_pip.log | tail -n 60
    exit 1
  fi
fi

OPENAI_API_KEY="$(printf '%s' "$KEY_B64" | base64 -d)"
OPENAI_MODEL="$(printf '%s' "$MODEL_B64" | base64 -d)"

pkill -f "uvicorn main:app" >/dev/null 2>&1 || true
nohup env OPENAI_API_KEY="$OPENAI_API_KEY" OPENAI_MODEL="$OPENAI_MODEL" \
  /usr/bin/python3 -m uvicorn main:app --host 0.0.0.0 --port "$BACKEND_PORT" \
  >/tmp/cvdoor_backend.log 2>&1 &

# Wait for startup
for i in $(seq 1 25); do
  if curl -sf "http://127.0.0.1:${BACKEND_PORT}/healthz" >/tmp/cvdoor_healthz.json; then
    break
  fi
  sleep 1
done

curl -sf "http://127.0.0.1:${BACKEND_PORT}/healthz"

OPT_RESP="$(curl -sS --max-time 120 -X POST "http://127.0.0.1:${BACKEND_PORT}/v1/optimize" \
  -H 'Content-Type: application/json' \
  -d '{"resume_text":"John Doe\nMarketing Assistant\nManaged social media channels, produced campaign reports, and coordinated content launches.","jd_text":"We are hiring a Marketing Assistant to support social media campaigns, analytics reporting, and cross-team coordination.","user_id":null}')"

python3 - <<'PY' "$OPT_RESP"
import json, sys
obj = json.loads(sys.argv[1])
cl = (obj.get("cover_letter") or "").strip()
if not cl:
    print("ERROR: optimize cover_letter empty")
    raise SystemExit(2)
print("OK optimize cover_letter_len:", len(cl))
PY

REG_RESP="$(curl -sS --max-time 120 -X POST "http://127.0.0.1:${BACKEND_PORT}/v1/generate-cover-letter" \
  -H 'Content-Type: application/json' \
  -d '{"resume_text":"John Doe\nMarketing Assistant\nManaged social media channels, produced campaign reports, and coordinated content launches.","jd_text":"We are hiring a Marketing Assistant to support social media campaigns, analytics reporting, and cross-team coordination.","user_id":null}')"

python3 - <<'PY' "$REG_RESP"
import json, sys
obj = json.loads(sys.argv[1])
cl = (obj.get("cover_letter") or "").strip()
if not cl:
    print("ERROR: regen cover_letter empty")
    raise SystemExit(3)
print("OK regen cover_letter_len:", len(cl))
PY
REMOTE

echo "[4/7] Verify public endpoint from local"
BASE_URL="http://${AWS_HOST}:${BACKEND_PORT}/"
curl -sS --max-time 20 "${BASE_URL}healthz" >/tmp/cvdoor_public_healthz.json
cat /tmp/cvdoor_public_healthz.json

echo "[5/7] Update app backend URL in local.properties"
if grep -q '^cvdoor.api.base.url=' local.properties; then
  sed -i "s|^cvdoor\.api\.base\.url=.*$|cvdoor.api.base.url=${BASE_URL}|" local.properties
else
  echo "cvdoor.api.base.url=${BASE_URL}" >> local.properties
fi

echo "[6/7] Build release APK"
if [[ "$REBUILD_APK" == "true" ]]; then
  export JAVA_HOME="${JAVA_HOME:-/usr/local/sdkman/candidates/java/21.0.10-ms}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
  ./gradlew assembleRelease --no-daemon
fi

echo "[7/7] APK fingerprints"
ls -lh app/build/outputs/apk/release/app-release.apk
md5sum app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk

echo "DONE"
echo "Backend URL: ${BASE_URL}"
echo "APK: app/build/outputs/apk/release/app-release.apk"
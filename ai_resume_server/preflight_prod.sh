#!/usr/bin/env bash
set -euo pipefail

# Production preflight for CVdoor backend.
# Usage:
#   DATABASE_URL='postgresql://user:pass@host:5432/db?sslmode=require' \
#   SESSION_TOKEN_SECRET='...' SERVER_API_KEY='...' OPENAI_API_KEY='...' \
#   STRIPE_SECRET_KEY='...' STRIPE_WEBHOOK_SECRET='...' \
#   STRIPE_PRICE_MONTHLY='price_...' STRIPE_PRICE_ANNUAL='price_...' \
#   DEBUG=0 DB_REQUIRE_PG_IN_PROD=1 DB_SSLMODE=require \
#   bash ai_resume_server/preflight_prod.sh

required=(
  OPENAI_API_KEY
  SESSION_TOKEN_SECRET
  SERVER_API_KEY
  DATABASE_URL
  GOOGLE_PLAY_PACKAGE_NAME
  STRIPE_SECRET_KEY
  STRIPE_WEBHOOK_SECRET
  STRIPE_PRICE_MONTHLY
  STRIPE_PRICE_ANNUAL
)

for key in "${required[@]}"; do
  if [[ -z "${!key:-}" ]]; then
    echo "[FAIL] missing env: ${key}"
    exit 1
  fi
done

if [[ "${DEBUG:-0}" != "0" ]]; then
  echo "[FAIL] DEBUG must be 0 in production"
  exit 1
fi

if [[ "${DB_REQUIRE_PG_IN_PROD:-1}" != "1" ]]; then
  echo "[FAIL] DB_REQUIRE_PG_IN_PROD must be 1 in production"
  exit 1
fi

if [[ "${DATABASE_URL}" != postgresql://* && "${DATABASE_URL}" != postgres://* ]]; then
  echo "[FAIL] DATABASE_URL must be PostgreSQL URL"
  exit 1
fi

if [[ "${GOOGLE_PLAY_PACKAGE_NAME}" != *.* ]]; then
  echo "[FAIL] GOOGLE_PLAY_PACKAGE_NAME must be a valid Android package name"
  exit 1
fi

if [[ "${DB_SSLMODE:-require}" != "require" && "${DB_SSLMODE:-require}" != "verify-full" ]]; then
  echo "[FAIL] DB_SSLMODE should be require or verify-full"
  exit 1
fi

if [[ -z "${STRIPE_PRICE_ONE_TIME:-}" ]]; then
  echo "[WARN] STRIPE_PRICE_ONE_TIME is empty. One-time purchase will be unavailable."
fi

if [[ -z "${SMTP_HOST:-}" && -z "${TWILIO_ACCOUNT_SID:-}" ]]; then
  if [[ "${AUTH_DEV_DELIVERY:-0}" != "1" ]]; then
    echo "[WARN] OTP delivery providers are not configured (SMTP/Twilio missing)."
    echo "       Auth OTP, email verification, and reset-password delivery will fail in production."
  fi
fi

echo "[PASS] env preflight checks passed"

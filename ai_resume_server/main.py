"""
CVDoor / CVATS.AI  Backend  v2.0.0
Endpoints:
  POST /v1/optimize/start          — async job start, returns {job_id}
  GET  /v1/optimize/status/{job_id} — poll job result
  POST /v1/auth/session            — issue session token
  POST /v1/preview                 — demo preview (no billing required)
  POST /v1/generate-cover-letter   — standalone cover-letter generation
  POST /v1/cover-letter/variants   — A/B variants
  POST /v1/billing/verify          — verify Google Play purchase
  GET  /v1/entitlements/{user_id}  — check Pro subscription status
  GET  /v1/records?limit=          — list optimization records
  DELETE /v1/records/{id}          — delete a record
  POST /v1/records/clear           — clear all records
  DELETE /v1/account/delete        — GDPR full data erasure
  GET  /v1/quality/failures        — internal quality review
  GET  /v1/admin/spending          — real-time OpenAI cost dashboard
  GET  /healthz                    — health check
"""
from fastapi import FastAPI, HTTPException, Query, Header, BackgroundTasks, Request
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, FileResponse
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from openai import OpenAI
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse
import os, json, traceback, time, sqlite3, threading, re, difflib, base64, hmac, hashlib, uuid, math, secrets, smtplib
from email.message import EmailMessage
from urllib import request as urlrequest

# ===== Sentry error monitoring =====
# Set SENTRY_DSN env var to activate. No-op if unset (SENTRY_DSN is empty).
try:
    import sentry_sdk
    from sentry_sdk.integrations.fastapi import FastApiIntegration
    _SENTRY_DSN = os.getenv("SENTRY_DSN", "").strip()
    if _SENTRY_DSN:
        sentry_sdk.init(
            dsn=_SENTRY_DSN,
            integrations=[FastApiIntegration()],
            traces_sample_rate=0.05,  # 5% perf traces — cheap
            send_default_pii=False,   # never send resume/cover-letter content to Sentry
        )
except ImportError:
    pass  # sentry-sdk not installed in dev; non-fatal

# ===== 环境 =====
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
if not OPENAI_API_KEY:
    raise RuntimeError("环境变量 OPENAI_API_KEY 未设置")

OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
DEBUG = os.getenv("DEBUG", "1") == "1"
DB_PATH = os.getenv("DB_PATH", "cvdoor.db")
SERVER_API_KEY = os.getenv("SERVER_API_KEY", "")
# Session token signing secret. MUST be set independently of OPENAI_API_KEY in
# production; leaking the OpenAI key would otherwise let an attacker mint
# session tokens for any user.
SESSION_TOKEN_SECRET = os.getenv("SESSION_TOKEN_SECRET", "")
SESSION_TOKEN_TTL_SEC = int(os.getenv("SESSION_TOKEN_TTL_SEC", "86400"))
if not SESSION_TOKEN_SECRET:
    if os.getenv("ALLOW_INSECURE_SESSION_SECRET") == "1":
        # Dev / local only — explicit opt-in.
        SESSION_TOKEN_SECRET = OPENAI_API_KEY
        print("WARNING: SESSION_TOKEN_SECRET unset; falling back to OPENAI_API_KEY (dev only).")
    else:
        raise RuntimeError(
            "SESSION_TOKEN_SECRET must be set in production. Generate one with: "
            "python -c 'import secrets; print(secrets.token_urlsafe(48))'"
        )

# ===== Google Play subscription verification =====
# Set GOOGLE_PLAY_PACKAGE_NAME (e.g. "com.synmodel.cvdoor") and provide a service
# account JSON either via GOOGLE_APPLICATION_CREDENTIALS (file path) or
# GOOGLE_PLAY_SERVICE_ACCOUNT_JSON (raw JSON, useful on PaaS without a writable
# filesystem). When unset, /v1/billing/verify rejects ALL purchases unless
# ALLOW_INSECURE_BILLING=1 (dev only).
GOOGLE_PLAY_PACKAGE_NAME = os.getenv("GOOGLE_PLAY_PACKAGE_NAME", "").strip()
GOOGLE_PLAY_SA_JSON_RAW = os.getenv("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "").strip()
GOOGLE_PLAY_SA_FILE = os.getenv("GOOGLE_APPLICATION_CREDENTIALS", "").strip()
ALLOW_INSECURE_BILLING = os.getenv("ALLOW_INSECURE_BILLING") == "1"

# Rate limiting - protects OpenAI bill from abusive clients
RATE_LIMIT_OPTIMIZE_PER_HOUR = int(os.getenv("RATE_LIMIT_OPTIMIZE_PER_HOUR", "10"))
RATE_LIMIT_PREVIEW_PER_HOUR = int(os.getenv("RATE_LIMIT_PREVIEW_PER_HOUR", "20"))
# Retention endpoints (weave / interview) use heavier model calls, so tighter cap.
RATE_LIMIT_RETENTION_PER_HOUR = int(os.getenv("RATE_LIMIT_RETENTION_PER_HOUR", "6"))

# Freemium gating - non-Pro users get FREE_OPTIMIZE_LIFETIME full optimizations
# total (lifetime). Beyond that they must subscribe. This is the single
# biggest trial→paid conversion lever; tune via env without redeploy.
FREE_OPTIMIZE_LIFETIME = int(os.getenv("FREE_OPTIMIZE_LIFETIME", "1"))
# Non-Pro users receive a readable preview watermark in generated text.
TRIAL_WATERMARK_LINE = os.getenv(
    "TRIAL_WATERMARK_LINE",
    "[CVdoor Trial Preview] Upgrade to Pro to unlock export-ready full text.",
)
TRIAL_WATERMARK_EVERY_N_LINES = int(os.getenv("TRIAL_WATERMARK_EVERY_N_LINES", "3"))

# Launch-readiness gates (tunable via env without code change)
LAUNCH_GATE_RISKY_RATE_MAX = float(os.getenv("LAUNCH_GATE_RISKY_RATE_MAX", "0.15"))
LAUNCH_GATE_PLACEHOLDER_RATE_MAX = float(os.getenv("LAUNCH_GATE_PLACEHOLDER_RATE_MAX", "0.05"))
LAUNCH_GATE_RETENTION_7D_MIN = float(os.getenv("LAUNCH_GATE_RETENTION_7D_MIN", "0.12"))
# DB production hardening
DB_REQUIRE_PG_IN_PROD = os.getenv("DB_REQUIRE_PG_IN_PROD", "1") == "1"
DB_SSLMODE = os.getenv("DB_SSLMODE", "require").strip() or "require"
DB_POOL_MIN_CONN = int(os.getenv("DB_POOL_MIN_CONN", "1"))
DB_POOL_MAX_CONN = int(os.getenv("DB_POOL_MAX_CONN", "15"))
DB_CONNECT_TIMEOUT_SEC = int(os.getenv("DB_CONNECT_TIMEOUT_SEC", "8"))
# Pro users get a generous monthly cap (prevents API-cost bleed from abusers).
# 200/month = ~6.7/day which is well above any real job-hunter's usage.
# Marginal API cost at 200 calls × $0.013/call (gpt-4o) = $2.60 vs $19 revenue → 86% margin.
PRO_OPTIMIZE_PER_MONTH = int(os.getenv("PRO_OPTIMIZE_PER_MONTH", "200"))

# Auth hardening settings (OTP / verification / reset)
AUTH_OTP_TTL_SEC = int(os.getenv("AUTH_OTP_TTL_SEC", "600"))
AUTH_OTP_RESEND_COOLDOWN_SEC = int(os.getenv("AUTH_OTP_RESEND_COOLDOWN_SEC", "45"))
AUTH_OTP_MAX_ATTEMPTS = int(os.getenv("AUTH_OTP_MAX_ATTEMPTS", "5"))
AUTH_RESET_MIN_PASSWORD_LEN = int(os.getenv("AUTH_RESET_MIN_PASSWORD_LEN", "8"))

SMTP_HOST = os.getenv("SMTP_HOST", "").strip()
SMTP_PORT = int(os.getenv("SMTP_PORT", "587"))
SMTP_USER = os.getenv("SMTP_USER", "").strip()
SMTP_PASS = os.getenv("SMTP_PASS", "").strip()
SMTP_FROM = os.getenv("SMTP_FROM", "no-reply@cvdoor.app").strip()

TWILIO_ACCOUNT_SID = os.getenv("TWILIO_ACCOUNT_SID", "").strip()
TWILIO_AUTH_TOKEN = os.getenv("TWILIO_AUTH_TOKEN", "").strip()
TWILIO_FROM_NUMBER = os.getenv("TWILIO_FROM_NUMBER", "").strip()

# In debug this can return dev_code to unblock QA without SMTP/Twilio.
AUTH_DEV_DELIVERY = os.getenv("AUTH_DEV_DELIVERY", "1" if DEBUG else "0") == "1"

# Two-tier model strategy for quality + cost balance:
#  - PREMIUM model writes the cover letter draft + rewrite (user-visible quality)
#  - FAST model does cheap auxiliary work: entity extraction, JSON evaluation,
#    keyword scoring, brief synthesis. Mini-class models are sufficient there.
# Set OPENAI_MODEL_PREMIUM=gpt-4o (default) for production; downgrade to mini
# only for dev/QA cost control.
OPENAI_MODEL_PREMIUM = os.getenv("OPENAI_MODEL_PREMIUM", "gpt-4o")
OPENAI_MODEL_FAST = os.getenv("OPENAI_MODEL_FAST", OPENAI_MODEL)
OPENAI_MAX_RETRIES = int(os.getenv("OPENAI_MAX_RETRIES", "3"))

CORS_ALLOWED_ORIGINS = [
    x.strip() for x in os.getenv("CORS_ALLOWED_ORIGINS", "*").split(",") if x.strip()
]

client = OpenAI(api_key=OPENAI_API_KEY)


# OpenAI pricing per 1M tokens (update when OpenAI changes pricing)
_MODEL_PRICING: Dict[str, Dict[str, float]] = {
    "gpt-4o":       {"input": 2.50, "output": 10.00},
    "gpt-4o-mini":  {"input": 0.15, "output": 0.60},
    "gpt-4.1":      {"input": 2.00, "output": 8.00},
    "gpt-4.1-mini": {"input": 0.40, "output": 1.60},
}

def _compute_cost(model: str, prompt_tokens: int, completion_tokens: int) -> float:
    """Return USD cost for a single API call given token counts."""
    # Match by prefix so gpt-4o-mini-2024-07-18 etc. are handled
    for key, prices in _MODEL_PRICING.items():
        if model.startswith(key):
            return (prompt_tokens * prices["input"] + completion_tokens * prices["output"]) / 1_000_000
    # Unknown model — assume gpt-4o pricing (conservative overestimate)
    return (prompt_tokens * 2.50 + completion_tokens * 10.00) / 1_000_000

# Thread-local storage for passing user_id into _chat_completion without
# changing every call site's signature.
_spend_context = threading.local()

def _record_spend(model: str, endpoint: str, usage, user_id: str = "") -> None:
    """Persist token usage + USD cost to api_spend table. Non-fatal on error."""
    try:
        pt = getattr(usage, "prompt_tokens", 0) or 0
        ct = getattr(usage, "completion_tokens", 0) or 0
        tt = getattr(usage, "total_tokens", 0) or (pt + ct)
        cost = _compute_cost(model, pt, ct)
        with _db_lock:
            conn = get_db()
            try:
                conn.execute(
                    "INSERT INTO api_spend (model, endpoint, prompt_tokens, completion_tokens, "
                    "total_tokens, cost_usd, user_id, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    (model, endpoint, pt, ct, tt, cost, user_id or "", int(time.time())),
                )
                conn.commit()
            finally:
                conn.close()
    except Exception:
        pass  # Never let spend-recording crash the main request


def _chat_completion(
    *,
    messages: List[Dict[str, str]],
    premium: bool = False,
    temperature: Optional[float] = None,
    response_format: Optional[Dict[str, str]] = None,
    max_tokens: Optional[int] = None,
    _endpoint: str = "",
):
    """Thin OpenAI wrapper with model tiering + exponential-backoff retry.

    Rationale:
      - Cover-letter draft & rewrite need premium model for prose quality.
      - Evaluation / extraction can stay on the fast model (cheap).
      - OpenAI 429 / 5xx errors are transient: we retry up to OPENAI_MAX_RETRIES
        with exponential backoff so a single network hiccup doesn't fail the
        user's optimize job.
    """
    model = OPENAI_MODEL_PREMIUM if premium else OPENAI_MODEL_FAST
    kwargs: Dict[str, Any] = {"model": model, "messages": messages}
    if temperature is not None:
        kwargs["temperature"] = temperature
    if response_format is not None:
        kwargs["response_format"] = response_format
    if max_tokens is not None:
        kwargs["max_tokens"] = max_tokens
    last_exc: Optional[Exception] = None
    for attempt in range(max(1, OPENAI_MAX_RETRIES)):
        try:
            resp = client.chat.completions.create(**kwargs)
            # Record spend asynchronously (non-blocking, non-fatal)
            uid = getattr(_spend_context, "user_id", "")
            threading.Thread(
                target=_record_spend,
                args=(model, _endpoint, resp.usage, uid),
                daemon=True,
            ).start()
            return resp
        except Exception as e:  # openai.APIError, RateLimitError, APIConnectionError, ...
            last_exc = e
            msg = str(e).lower()
            # Only retry transient errors; permanent ones (auth, bad request) fail fast.
            transient = any(t in msg for t in ("rate limit", "timeout", "timed out", "connection", "503", "502", "500"))
            if not transient or attempt == OPENAI_MAX_RETRIES - 1:
                raise
            backoff = 0.5 * (2 ** attempt)
            if DEBUG:
                print(f"[openai retry {attempt + 1}/{OPENAI_MAX_RETRIES}] {type(e).__name__}: {e}; sleeping {backoff}s")
            time.sleep(backoff)
    assert last_exc is not None
    raise last_exc

# Cover letter generation heuristics:
# - keep enough resume context for personalization without exploding prompt size
# - ensure final cover letter has at least substantial body length
MAX_RESUME_CONTEXT_CHARS = 3500
RESUME_EXCERPT_HEAD_LINES = 10
RESUME_EXCERPT_TAIL_LINES = 10
MIN_COVER_LETTER_LENGTH = 180
MIN_COVER_LETTER_QUALITY_SCORE = int(os.getenv("MIN_COVER_LETTER_QUALITY_SCORE", "75"))

# ===== Database abstraction (SQLite for dev · PostgreSQL for production) =====
# Set DATABASE_URL to a postgres:// or postgresql:// URI to enable PostgreSQL.
# On Railway: auto-provided. On Heroku: DATABASE_URL env var.
# If DATABASE_URL is absent, falls back to local SQLite (DB_PATH).
DATABASE_URL = os.getenv("DATABASE_URL", "").strip()
DB_IS_PG = DATABASE_URL.startswith(("postgres://", "postgresql://"))


def _normalize_database_url(url: str) -> str:
    """Normalize Postgres URL and enforce sslmode for managed DB providers (RDS)."""
    if not url:
        return url
    normalized = url.replace("postgres://", "postgresql://", 1)
    if not normalized.startswith("postgresql://"):
        return normalized
    parsed = urlparse(normalized)
    query = dict(parse_qsl(parsed.query, keep_blank_values=True))
    query.setdefault("sslmode", DB_SSLMODE)
    return urlunparse(parsed._replace(query=urlencode(query)))


DATABASE_URL = _normalize_database_url(DATABASE_URL)

if DB_IS_PG:
    try:
        import psycopg2
        import psycopg2.extras
        import psycopg2.pool
    except ImportError:
        raise RuntimeError(
            "psycopg2-binary required for PostgreSQL. "
            "Add it to requirements.txt and reinstall."
        )

_db_lock = threading.Lock()


class _DBConn:
    """Thin adapter that gives sqlite3 and psycopg2 a unified interface.

    Key normalisations:
    - execute(sql, params) translates '?' placeholders to '%s' for psycopg2.
    - commit() / close() delegate to the underlying connection.
    - Rows from both backends support dict-style column access (row["col"]).
    """

    def __init__(self, inner, pg: bool, releaser=None):
        self._conn = inner
        self._pg = pg
        self._releaser = releaser

    def _q(self, sql: str) -> str:
        return sql.replace("?", "%s") if self._pg else sql

    def execute(self, sql: str, params=()):
        if self._pg:
            cur = self._conn.cursor()
            cur.execute(self._q(sql), params)
            return cur
        else:
            return self._conn.execute(sql, params)

    def cursor(self):
        return self._conn.cursor()

    def commit(self):
        self._conn.commit()

    def close(self):
        if self._releaser:
            self._releaser(self._conn)
        else:
            self._conn.close()

    # ------------------------------------------------------------------
    def insert_get_id(self, sql: str, params: tuple) -> int:
        """Run an INSERT and return the auto-generated primary key.

        PostgreSQL: appends RETURNING id and fetches it.
        SQLite:     uses cursor.lastrowid.
        """
        if self._pg:
            returning = self._q(sql.rstrip().rstrip(";")) + " RETURNING id"
            cur = self._conn.cursor()
            cur.execute(returning, params)
            return cur.fetchone()["id"]
        else:
            cur = self._conn.execute(sql, params)
            return cur.lastrowid

    def list_tables(self) -> set:
        """Return set of table names that currently exist in the DB."""
        if self._pg:
            cur = self._conn.cursor()
            cur.execute(
                "SELECT table_name FROM information_schema.tables "
                "WHERE table_schema = 'public'"
            )
            return {row["table_name"] for row in cur.fetchall()}
        else:
            return {
                row[0]
                for row in self._conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                ).fetchall()
            }


_pg_pool = None
_pg_pool_lock = threading.Lock()


def _get_pg_pool():
    global _pg_pool
    if _pg_pool is not None:
        return _pg_pool
    with _pg_pool_lock:
        if _pg_pool is None:
            _pg_pool = psycopg2.pool.ThreadedConnectionPool(
                minconn=max(1, DB_POOL_MIN_CONN),
                maxconn=max(max(1, DB_POOL_MIN_CONN), DB_POOL_MAX_CONN),
                dsn=DATABASE_URL,
                cursor_factory=psycopg2.extras.RealDictCursor,
                connect_timeout=max(3, DB_CONNECT_TIMEOUT_SEC),
                application_name="cvdoor-backend",
                keepalives=1,
                keepalives_idle=30,
                keepalives_interval=10,
                keepalives_count=3,
            )
        return _pg_pool


def get_db() -> _DBConn:
    if DB_IS_PG:
        pool = _get_pg_pool()
        inner = pool.getconn()
        inner.autocommit = False
        return _DBConn(inner, True, releaser=pool.putconn)
    else:
        inner = sqlite3.connect(DB_PATH, check_same_thread=False)
        inner.row_factory = sqlite3.Row
        return _DBConn(inner, False)


def _ddl_pk() -> str:
    """Auto-increment primary key syntax for the active database."""
    return "SERIAL PRIMARY KEY" if DB_IS_PG else "INTEGER PRIMARY KEY AUTOINCREMENT"


def init_db():
    with _db_lock:
        conn = get_db()
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS records (
            id            {_ddl_pk()},
            user_id       TEXT    NOT NULL,
            resume_text   TEXT    NOT NULL,
            jd_text       TEXT    NOT NULL,
            optimized_text TEXT   NOT NULL,
            before_total  INTEGER NOT NULL DEFAULT 0,
            after_total   INTEGER NOT NULL DEFAULT 0,
            dims_before   TEXT    NOT NULL DEFAULT '[]',
            dims_after    TEXT    NOT NULL DEFAULT '[]',
            analysis_json TEXT    DEFAULT NULL,
            created_at    INTEGER NOT NULL
        )""")
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS users (
            id             {_ddl_pk()},
            user_id        TEXT    NOT NULL UNIQUE,
            email          TEXT    DEFAULT NULL,
            phone          TEXT    DEFAULT NULL,
            password_hash  TEXT    DEFAULT NULL,
            name           TEXT    NOT NULL DEFAULT '',
            provider       TEXT    NOT NULL DEFAULT 'password',
            google_sub     TEXT    DEFAULT NULL,
            created_at     INTEGER NOT NULL,
            updated_at     INTEGER NOT NULL
        )""")
        for ddl in [
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email) WHERE email IS NOT NULL AND email <> ''",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone ON users(phone) WHERE phone IS NOT NULL AND phone <> ''",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_google_sub ON users(google_sub) WHERE google_sub IS NOT NULL AND google_sub <> ''",
        ]:
            try:
                conn.execute(ddl)
            except Exception:
                # Keep startup resilient even if historical data violates legacy constraints.
                pass
        conn.execute("CREATE INDEX IF NOT EXISTS idx_users_uid ON users(user_id)")
        # Safe schema evolution for existing DBs.
        user_cols = [
            "email_verified INTEGER NOT NULL DEFAULT 0",
            "phone_verified INTEGER NOT NULL DEFAULT 0",
            "status TEXT NOT NULL DEFAULT 'active'",
            "last_login_at INTEGER DEFAULT NULL",
        ]
        for col in user_cols:
            try:
                if DB_IS_PG:
                    conn.execute(f"ALTER TABLE users ADD COLUMN IF NOT EXISTS {col}")
                else:
                    conn.execute(f"ALTER TABLE users ADD COLUMN {col}")
            except Exception:
                pass
        conn.execute("CREATE INDEX IF NOT EXISTS idx_users_status ON users(status)")
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS auth_otps (
            id            {_ddl_pk()},
            channel       TEXT    NOT NULL,
            target        TEXT    NOT NULL,
            purpose       TEXT    NOT NULL,
            code_hash     TEXT    NOT NULL,
            expires_at    INTEGER NOT NULL,
            consumed_at   INTEGER DEFAULT NULL,
            attempts      INTEGER NOT NULL DEFAULT 0,
            created_at    INTEGER NOT NULL
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_auth_otps_lookup ON auth_otps(channel, target, purpose, created_at)")
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS quality_failures (
            id             {_ddl_pk()},
            reason         TEXT    NOT NULL,
            quality_json   TEXT    NOT NULL,
            required_info  TEXT    NOT NULL DEFAULT '[]',
            resume_excerpt TEXT    NOT NULL,
            jd_excerpt     TEXT    NOT NULL,
            cover_letter   TEXT    NOT NULL,
            created_at     INTEGER NOT NULL
        )""")
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS regression_cases (
            id          {_ddl_pk()},
            name        TEXT    NOT NULL UNIQUE,
            resume_text TEXT    NOT NULL,
            jd_text     TEXT    NOT NULL,
            style       TEXT    DEFAULT NULL,
            industry    TEXT    DEFAULT NULL,
            seniority   TEXT    DEFAULT NULL,
            region      TEXT    DEFAULT NULL,
            tone        TEXT    DEFAULT NULL,
            min_score   INTEGER NOT NULL DEFAULT 75,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )""")
        # Subscription entitlements - source of truth for paywall gating.
        # Stores the latest verified purchase per (user_id, product_id).
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS entitlements (
            id              {_ddl_pk()},
            user_id         TEXT    NOT NULL,
            product_id      TEXT    NOT NULL,
            purchase_token  TEXT    NOT NULL,
            order_id        TEXT    DEFAULT NULL,
            plan            TEXT    NOT NULL DEFAULT '',
            expires_at      INTEGER NOT NULL DEFAULT 0,
            auto_renewing   INTEGER NOT NULL DEFAULT 1,
            is_active       INTEGER NOT NULL DEFAULT 0,
            created_at      INTEGER NOT NULL,
            updated_at      INTEGER NOT NULL,
            UNIQUE(user_id, product_id)
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_entitlements_user ON entitlements(user_id)")
        # Persistent async job store — survives server restarts.
        # status: "pending" | "running" | "done" | "error"
        # Running jobs that existed before restart are marked "error" since their
        # background thread is gone; the app will show "failed, please retry".
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS jobs (
            job_id      TEXT    PRIMARY KEY,
            status      TEXT    NOT NULL DEFAULT 'pending',
            result_json TEXT    DEFAULT NULL,
            error       TEXT    DEFAULT NULL,
            created_at  REAL    NOT NULL,
            updated_at  REAL    NOT NULL
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_jobs_updated ON jobs(updated_at)")
        # ── Retention: job application tracker ──
        # One row per JD the user is targeting. Drives the "I have 5 pending
        # apps, let me check on them" return visits.
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS job_applications (
            id          {_ddl_pk()},
            user_id     TEXT    NOT NULL,
            company     TEXT    NOT NULL DEFAULT '',
            role        TEXT    NOT NULL DEFAULT '',
            jd_text     TEXT    NOT NULL DEFAULT '',
            status      TEXT    NOT NULL DEFAULT 'saved',
            match_score INTEGER NOT NULL DEFAULT 0,
            notes       TEXT    NOT NULL DEFAULT '',
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_apps_user ON job_applications(user_id, updated_at)")
        # ── Retention: resume version history ──
        # Snapshots each optimized version so we can show a score-progression
        # chart and diff between versions (Rezi/Teal-style).
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS resume_versions (
            id              {_ddl_pk()},
            user_id         TEXT    NOT NULL,
            application_id  INTEGER DEFAULT NULL,
            label           TEXT    NOT NULL DEFAULT '',
            resume_text     TEXT    NOT NULL,
            match_score     INTEGER NOT NULL DEFAULT 0,
            created_at      INTEGER NOT NULL
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_versions_user ON resume_versions(user_id, created_at)")
        # Retention interaction telemetry for health scoring and dropoff analysis.
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS user_events (
            id          {_ddl_pk()},
            user_id     TEXT    NOT NULL,
            event_name  TEXT    NOT NULL,
            event_meta  TEXT    NOT NULL DEFAULT '{{}}',
            created_at  INTEGER NOT NULL
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_user_events_user_time ON user_events(user_id, created_at)")
        # ── API spend tracking ──
        # Records every OpenAI call with token counts and computed USD cost.
        # Enables real-time cost monitoring via /v1/admin/spending.
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS api_spend (
            id                {_ddl_pk()},
            model             TEXT    NOT NULL,
            endpoint          TEXT    NOT NULL DEFAULT '',
            prompt_tokens     INTEGER NOT NULL DEFAULT 0,
            completion_tokens INTEGER NOT NULL DEFAULT 0,
            total_tokens      INTEGER NOT NULL DEFAULT 0,
            cost_usd          REAL    NOT NULL DEFAULT 0,
            user_id           TEXT    NOT NULL DEFAULT '',
            created_at        INTEGER NOT NULL
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_spend_created ON api_spend(created_at)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_spend_model ON api_spend(model, created_at)")
        # Reliability events for launch hardening and incident tracking.
        conn.execute(f"""
        CREATE TABLE IF NOT EXISTS reliability_events (
            id           {_ddl_pk()},
            event_type   TEXT    NOT NULL,
            severity     TEXT    NOT NULL,
            detail_json  TEXT    NOT NULL DEFAULT '{{}}',
            created_at   INTEGER NOT NULL
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_reliability_created ON reliability_events(created_at)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_reliability_type ON reliability_events(event_type, created_at)")
        # Mark any orphaned "running" jobs as "error" — they won't complete.
        conn.execute(
            "UPDATE jobs SET status='error', error='Server restarted while job was running', updated_at=? "
            "WHERE status IN ('pending','running')",
            (time.time(),),
        )
        conn.commit()
        conn.close()

init_db()

# ===== Subscription plan registry =====
# Maps Google Play product IDs to plan code + duration in seconds.
# Update here whenever Play Console product catalogue changes.
SUBSCRIPTION_PLANS = {
    "cvdoor_pro_monthly": {"plan": "monthly", "duration_sec": 31 * 86400},
    "cvdoor_pro_annual":  {"plan": "annual",  "duration_sec": 366 * 86400},
}

# INAPP (non-consumable) products — verified via purchases().products().get()
# rather than the subscriptions API. Duration represents a single short access window.
INAPP_PRODUCTS: Dict[str, Dict[str, Any]] = {
    "cvdoor_one_optimize": {"plan": "one_time", "duration_sec": 7 * 86400},
}

# ===== IO Models =====
class OptimizeReq(BaseModel):
    # Hard caps prevent DoS via 10MB blob and also keep OpenAI cost predictable.
    resume_text: str = Field(..., min_length=1, max_length=50_000)
    jd_text: str = Field(..., min_length=1, max_length=20_000)
    user_id: Optional[str] = Field(None, max_length=128)
    style: Optional[str] = Field(None, max_length=64)
    industry: Optional[str] = Field(None, max_length=128)
    seniority: Optional[str] = Field(None, max_length=64)
    region: Optional[str] = Field(None, max_length=64)
    tone: Optional[str] = Field(None, max_length=64)

class SessionCreateReq(BaseModel):
    user_id: str

class RegisterReq(BaseModel):
    email: Optional[str] = Field(None, max_length=320)
    phone: Optional[str] = Field(None, max_length=32)
    password: Optional[str] = Field(None, min_length=6, max_length=128)
    name: Optional[str] = Field(None, max_length=128)
    email_otp_code: Optional[str] = Field(None, max_length=12)
    phone_otp_code: Optional[str] = Field(None, max_length=12)

class LoginReq(BaseModel):
    identifier: str = Field(..., min_length=3, max_length=320)  # email or phone
    password: str = Field(..., min_length=6, max_length=128)

class GoogleAuthReq(BaseModel):
    id_token: str = Field(..., min_length=20, max_length=4096)

class AuthUserResp(BaseModel):
    user_id: str
    email: Optional[str] = None
    phone: Optional[str] = None
    name: str = ""
    provider: str = "password"
    email_verified: bool = False
    phone_verified: bool = False
    status: str = "active"
    session_token: str
    expires_at: int

class GuestSessionReq(BaseModel):
    """Used by web clients and new devices to bootstrap a session token
    without embedding the server API key."""
    device_id: Optional[str] = Field(None, max_length=128)
    platform: Optional[str] = Field("web", max_length=16)

class SessionResp(BaseModel):
    session_token: str
    expires_at: int

class SuggestionItemOut(BaseModel):
    level: str = "should_improve"
    suggestion: str = ""
    evidence: str = ""
    priority: int = 3
    expected_impact: str = ""

class DimAnalysisOut(BaseModel):
    name: str
    before: int
    after: int
    reasons: List[str] = []
    problems: List[str] = []
    suggestions: List[str] = []
    missing_before: List[str] = []
    added_after: List[str] = []

class OverallAnalysisOut(BaseModel):
    summary: str = ""
    strengths: List[str] = []
    issues: List[str] = []
    actions: List[str] = []
    must_fix: List[str] = []
    should_improve: List[str] = []
    could_optimize: List[str] = []
    data_gaps: List[str] = []
    suggestion_items: List[SuggestionItemOut] = []

class AnalysisOut(BaseModel):
    overall: OverallAnalysisOut = OverallAnalysisOut()
    dimensions: List[DimAnalysisOut] = []


class ATSScreeningOut(BaseModel):
    internal_score: int = 0
    pass_probability: int = 0
    verdict: str = "reject"  # pass | borderline | reject
    hard_fail_reasons: List[str] = []
    must_fix: List[str] = []
    signal_breakdown: Dict[str, int] = {}
    keyword_coverage_pct: float = 0.0
    quantified_bullet_ratio: float = 0.0

class OptimizeResp(BaseModel):
    optimized: str
    before_total: int = Field(ge=0, le=100)
    after_total:  int = Field(ge=0, le=100)
    score_delta: int = 0
    dims_before: List[int] = []
    dims_after:  List[int] = []
    match_score: Optional[int] = None
    added_keywords: List[str] = []
    value_proof: List[str] = []
    preview_locked: bool = False
    upgrade_cta: str = ""
    ats_screening: Optional[ATSScreeningOut] = None
    cover_letter: Optional[str] = None
    cover_letter_quality: Optional[Dict[str, Any]] = None
    analysis: Optional[AnalysisOut] = None
    record_id:  Optional[int] = None
    created_at: Optional[int] = None
    session_token: Optional[str] = None
    session_expires_at: Optional[int] = None
    cover_letter_status: Optional[str] = None
    need_more_info: bool = False
    required_info: List[str] = []

class RecordOut(BaseModel):
    id: int
    user_id: str
    created_at: int
    resume_text: str
    jd_text: str
    optimized_text: str
    before_total: int
    after_total: int
    dims_before: List[int]
    dims_after: List[int]
    analysis: Optional[AnalysisOut] = None

class QualityFailureOut(BaseModel):
    id: int
    reason: str
    quality: Dict[str, Any] = {}
    required_info: List[str] = []
    resume_excerpt: str = ""
    jd_excerpt: str = ""
    cover_letter: str = ""
    created_at: int

class RegressionCaseIn(BaseModel):
    name: str
    resume_text: str
    jd_text: str
    style: Optional[str] = None
    industry: Optional[str] = None
    seniority: Optional[str] = None
    region: Optional[str] = None
    tone: Optional[str] = None
    min_score: int = Field(default=75, ge=0, le=100)

class RegressionCaseOut(RegressionCaseIn):
    id: int
    created_at: int
    updated_at: int

class RegressionRunItem(BaseModel):
    case_id: int
    name: str
    score: int
    pass_threshold: int
    passed: bool
    feedback: List[str] = []

class RegressionRunResp(BaseModel):
    total: int
    passed: int
    failed: int
    pass_rate: float
    items: List[RegressionRunItem]


class OptimizeQualityAuditItem(BaseModel):
    record_id: int
    user_id: str = ""
    created_at: int
    before_total: int
    after_total: int
    score_delta: int
    similarity: float
    growth_pct: float
    quantified_bullet_ratio: float
    weak_verb_hits: int
    risky: bool = False
    issues: List[str] = []


class OptimizeQualityAuditResp(BaseModel):
    total: int
    risky_count: int
    risky_rate: float
    avg_similarity: float
    avg_growth_pct: float
    avg_score_delta: float
    avg_quantified_ratio: float
    items: List[OptimizeQualityAuditItem] = []


class RetentionEventReq(BaseModel):
    user_id: str
    event_name: str = Field(..., min_length=2, max_length=64)
    event_meta: Dict[str, Any] = {}


class RetentionHealthResp(BaseModel):
    user_id: str
    churn_risk_score: int
    retention_stage: str
    last_active_days_ago: int
    metrics: Dict[str, Any] = {}
    next_best_actions: List[str] = []


class RetentionOverviewResp(BaseModel):
    window_days: int
    users_observed: int
    active_users: int
    slipping_users: int
    at_risk_users: int
    retention_rate_7d: float
    reactivation_opportunity: int
    top_dropoff_signals: List[str] = []


class ReadinessGateOut(BaseModel):
    name: str
    passed: bool
    value: float = 0.0
    threshold: float = 0.0
    note: str = ""


class LaunchReadinessResp(BaseModel):
    ready: bool
    generated_at: int
    gates: List[ReadinessGateOut] = []
    metrics: Dict[str, Any] = {}


class ReactivationCandidateOut(BaseModel):
    user_id: str
    churn_risk_score: int
    retention_stage: str
    last_active_days_ago: int
    next_best_actions: List[str] = []


class ReactivationQueueResp(BaseModel):
    total_candidates: int
    items: List[ReactivationCandidateOut] = []


class ReliabilityEventOut(BaseModel):
    event_type: str
    severity: str
    detail: Dict[str, Any] = {}
    created_at: int


class ReliabilityOverviewResp(BaseModel):
    hours: int
    total_events: int
    error_events: int
    warning_events: int
    optimize_success_count: int
    optimize_error_count: int
    optimize_p95_ms: float = 0.0
    latest: List[ReliabilityEventOut] = []


class GrowthAttributionReq(BaseModel):
    user_id: str
    source: str = Field(..., min_length=1, max_length=64)
    medium: Optional[str] = Field(None, max_length=64)
    campaign: Optional[str] = Field(None, max_length=128)
    channel: Optional[str] = Field(None, max_length=64)
    landing_path: Optional[str] = Field(None, max_length=256)


class GrowthFunnelResp(BaseModel):
    days: int
    touched_users: int
    optimize_users: int
    paid_users: int
    touch_to_optimize: float
    optimize_to_paid: float
    touch_to_paid: float


class PlaceholderRepairResp(BaseModel):
    scanned: int
    repaired: int
    remaining_with_placeholders: int

# ===== FastAPI =====
app = FastAPI(title="CVATS.AI Backend", version="2.0.0")

# Startup safety checks — fail loud so misconfiguration is caught immediately
# rather than silently accepting fake payments in production.
@app.on_event("startup")
async def _startup_checks():
    if ALLOW_INSECURE_BILLING and not DEBUG:
        _log_reliability_event(
            "startup_insecure_billing",
            "error",
            {"allow_insecure_billing": True, "debug": DEBUG},
        )
        raise RuntimeError(
            "ALLOW_INSECURE_BILLING=1 with DEBUG=0 is forbidden in production."
        )
    if os.getenv("ALLOW_INSECURE_SESSION_SECRET") == "1" and not DEBUG:
        import sys
        print(
            "\n\033[91m[WARN] ALLOW_INSECURE_SESSION_SECRET=1 in non-debug mode. "
            "Set SESSION_TOKEN_SECRET to a strong random value.\033[0m\n",
            file=sys.stderr
        )
        _log_reliability_event(
            "startup_insecure_session_secret",
            "warning",
            {"allow_insecure_session_secret": True, "debug": DEBUG},
        )
    _log_reliability_event(
        "startup_ready",
        "info",
        {"db_backend": "postgresql" if DB_IS_PG else "sqlite", "model": OPENAI_MODEL},
    )
app.add_middleware(
    CORSMiddleware,
    allow_origins=CORS_ALLOWED_ORIGINS,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-API-Key"],
)

# Baseline security headers — cheap protection against clickjacking,
# MIME sniffing, and accidental http:// downgrades on web embeds.
@app.middleware("http")
async def _security_headers(request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault(
        "Strict-Transport-Security", "max-age=31536000; includeSubDomains"
    )
    return response


# Turn raw OpenAI failures into user-friendly HTTP responses instead of leaking
# a generic 500. Without this, a transient OpenAI outage looks like an app bug.
try:
    from openai import APIError as _OpenAIAPIError, APIConnectionError as _OpenAIConnErr, RateLimitError as _OpenAIRateErr  # type: ignore
except Exception:  # pragma: no cover - older openai-python fallback
    _OpenAIAPIError = Exception  # type: ignore
    _OpenAIConnErr = Exception   # type: ignore
    _OpenAIRateErr = Exception   # type: ignore


@app.exception_handler(_OpenAIRateErr)
async def _openai_rate_limited(_request, _exc):
    return JSONResponse(
        status_code=429,
        content={"detail": "AI service is busy. Please retry in ~30s."},
    )


@app.exception_handler(_OpenAIConnErr)
async def _openai_conn_failed(_request, _exc):
    return JSONResponse(
        status_code=503,
        content={"detail": "AI service unreachable. Please retry shortly."},
    )


@app.exception_handler(_OpenAIAPIError)
async def _openai_api_failed(_request, exc):
    # Don't leak provider error text to the client; log it server-side.
    if DEBUG:
        print(f"[openai api error] {type(exc).__name__}: {exc}")
    return JSONResponse(
        status_code=502,
        content={"detail": "Temporary AI service issue. Please retry."},
    )

# ===== Prompt =====
SYSTEM_PROMPT = """你是顶级 ATS 简历优化专家，拥有 10 年猎头与 HR 审核经验。你的唯一职责是把一份普通简历彻底改造成让招聘官眼前一亮、ATS 高分通过的专业版本。

【核心使命】
用户花钱买的不是"微调"，而是"脱胎换骨"。优化后的简历必须让用户一眼看出明显不同。

【绝对禁止】
- 只改几个词、只换同义词 — 这不叫优化
- 保留原文弱动词（负责/参与/协助）— 必须全部替换成强动词（主导/推动/构建/统筹）
- 保留无量化描述（"提升了效率"）— 必须改成"提升效率X%/节省Y小时"
- 编造数字 — 数字不明时用"（请补充：xx指标数字）"占位
- 在技能/语言/获奖/教育段落加量化placeholder — 只有工作经历bullet才需要量化

【ATS 硬规则】
1. 精确关键词：JD 原文怎么写就怎么植入，绝不缩写或替换同义词
2. 关键词双重覆盖：JD 必要技能必须同时出现在 (1) 自我介绍段 和 (2) 最相关经历bullet中
3. 标准章节标题：工作经验 / 教育背景 / 技能 / 自我介绍 / 获奖
4. Bullet 结构：每段经历至少 3 条 bullet，格式：强动词 + 具体行动 + 量化成果
5. 日期格式统一：全文一致用"YYYY年M月"或"YYYY.MM"

【每条bullet的改造步骤】
① 弱动词升级：负责→主导/统筹；参与→推动/协作完成；协助→支援/配合
② 添加方法细节：说明"怎么做到的"
③ 量化成果：加数字/百分比/规模；无数字则用placeholder提示
④ 植入JD精确词汇：自然融入JD要求的原文关键词

【输出格式】必须只输出 JSON（无其他文本）：

{
  "optimized": "<完整优化后简历，每条经历bullet必须经实质改造，比原文增加20-40%内容>",
  "before_total": <0-100，诚实评估原文ATS得分>,
  "after_total": <0-100，必须比before_total高15-25分>,
  "dims_before": [<关键词0-100>, <经验0-100>, <技能0-100>, <格式0-100>, <成就0-100>, <表达0-100>],
  "dims_after": [<同上6项，每项至少提升10-20分>],
  "added_keywords": ["已植入的JD原文关键词列表"],
  "cover_letter": "<求职信，语言必须与JD一致（中文JD→中文信），380-460字，必须：点出公司名和职位名，引用简历中真实成就，体现对岗位的具体理解>",
  "analysis": {
    "overall": {
      "summary": "<具体指出优化了哪些内容的2-3句总结>",
      "strengths": ["候选人真实优势"],
      "issues": ["原简历具体问题"],
      "actions": ["用户可立即执行的具体建议"]
    },
    "dimensions": [
      {"name": "Keywords", "before": <0-100>, "after": <0-100>, "reasons": ["原文缺哪些JD关键词"], "problems": ["具体问题"], "suggestions": ["具体建议"], "missing_before": ["JD要求但原文缺失的词"], "added_after": ["已植入的新词"]},
      {"name": "Experience", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []},
      {"name": "Skills", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []},
      {"name": "Format", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []},
      {"name": "Impact", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []},
      {"name": "Clarity", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []}
    ]
  }
}
"""

SYSTEM_PROMPT_EN = """You are a world-class ATS resume optimization expert with 10 years of recruiting and HR experience. Your sole mission: transform an ordinary resume into a professional document that impresses recruiters AND scores high with ATS systems.

CORE MISSION
Users pay for a complete transformation, not minor tweaks. The optimized resume must look obviously superior to the original.

ABSOLUTE PROHIBITIONS
- Only changing a few words or swapping synonyms — that is NOT optimization
- Keeping weak verbs (responsible for / assisted / participated) — replace ALL with strong verbs (led / drove / built / delivered)
- Keeping vague unquantified claims ("improved efficiency") — must become "improved efficiency by X% / saved Y hours"
- Fabricating numbers — use "(Please provide: [specific metric])" when real data is missing
- Adding quantification placeholders to Skills/Languages/Awards/Education bullets — only work experience bullets need quantification

ATS HARD RULES
1. Exact keyword matching: copy JD phrases verbatim — never abbreviate or use synonyms
2. Keyword double placement: every required JD skill MUST appear in BOTH (1) Summary/Profile AND (2) the most relevant experience bullet
3. Standard section headers: Work Experience / Education / Skills / Summary / Profile / Certifications / Awards
4. Bullet structure: ≥3 bullets per role, format: strong verb + specific action + quantified result
5. Consistent date format throughout: "Jan 2022 – Present" or "2022 – Present"

HOW TO MAKE EACH BULLET OBVIOUSLY BETTER
① Upgrade weak verbs: assisted→supported; responsible for→led; participated→drove
② Add method detail: explain HOW it was achieved
③ Quantify: add numbers/percentages/scale; use placeholder if unavailable
④ Embed JD keywords: weave exact JD phrases naturally into bullets

OUTPUT FORMAT: output ONLY valid JSON (no other text):

{
  "optimized": "<Full optimized resume — every experience bullet must be substantively rewritten, 20-40% longer than original>",
  "before_total": <0-100, honest ATS score of original>,
  "after_total": <0-100, must be 15-25 points higher than before_total>,
  "dims_before": [<keywords 0-100>, <experience 0-100>, <skills 0-100>, <format 0-100>, <impact 0-100>, <clarity 0-100>],
  "dims_after": [<same 6 scores after, each at least 10-20 points higher>],
  "added_keywords": ["exact JD phrases embedded in the resume"],
  "cover_letter": "<Cover letter in same language as JD (Chinese JD → Chinese letter), 250-320 words, must: name the company and role explicitly, cite real achievements from resume, show specific understanding of the role>",
  "analysis": {
    "overall": {
      "summary": "<2-3 sentences specifically describing what was improved>",
      "strengths": ["real candidate strengths"],
      "issues": ["specific problems in original resume"],
      "actions": ["immediately actionable advice for the user"]
    },
    "dimensions": [
      {"name": "Keywords", "before": <0-100>, "after": <0-100>, "reasons": ["which JD keywords were missing"], "problems": ["specific problems"], "suggestions": ["specific advice"], "missing_before": ["required by JD but absent"], "added_after": ["newly embedded keywords"]},
      {"name": "Experience", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []},
      {"name": "Skills", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []},
      {"name": "Format", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []},
      {"name": "Impact", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []},
      {"name": "Clarity", "before": <0-100>, "after": <0-100>, "reasons": [], "problems": [], "suggestions": [], "missing_before": [], "added_after": []}
    ]
  }
}
"""



def _build_user_msg(resume: str, jd: str) -> str:
    return f"【简历】\n{resume.strip()}\n\n【职位JD】\n{jd.strip()}"

def _normalize_cover_letter_style(style: Optional[str]) -> str:
    allowed = {"professional", "natural", "brief", "executive", "confident", "warm"}
    s = (style or "").strip().lower()
    return s if s in allowed else "professional"

def _normalize_cover_letter_tone(tone: Optional[str]) -> str:
    allowed = {"balanced", "assertive", "conservative"}
    s = (tone or "").strip().lower()
    return s if s in allowed else "balanced"

def _issue_session_token(user_id: str) -> tuple[str, int]:
    now = int(time.time())
    exp = now + max(300, SESSION_TOKEN_TTL_SEC)
    payload = {"uid": user_id, "exp": exp, "iat": now}
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    body = base64.urlsafe_b64encode(raw).decode("utf-8").rstrip("=")
    sig = hmac.new(
        SESSION_TOKEN_SECRET.encode("utf-8"),
        body.encode("utf-8"),
        hashlib.sha256,
    ).digest()
    sig_text = base64.urlsafe_b64encode(sig).decode("utf-8").rstrip("=")
    return f"{body}.{sig_text}", exp


def _norm_email(email: Optional[str]) -> str:
    return (email or "").strip().lower()


def _norm_phone(phone: Optional[str]) -> str:
    return re.sub(r"[^0-9+]", "", (phone or "").strip())


def _password_hash(password: str) -> str:
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 120_000)
    return base64.b64encode(salt + dk).decode("ascii")


def _password_verify(password: str, encoded: str) -> bool:
    try:
        raw = base64.b64decode((encoded or "").encode("ascii"))
        if len(raw) != 48:
            return False
        salt, target = raw[:16], raw[16:]
        dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 120_000)
        return hmac.compare_digest(dk, target)
    except Exception:
        return False


def _mask_target(channel: str, target: str) -> str:
    t = (target or "").strip()
    if not t:
        return ""
    if channel == "email" and "@" in t:
        name, domain = t.split("@", 1)
        if len(name) <= 2:
            return f"{name[:1]}***@{domain}"
        return f"{name[:2]}***@{domain}"
    if channel == "phone":
        keep = t[-4:] if len(t) >= 4 else t
        return f"***{keep}"
    return "***"


def _normalize_otp_channel(channel: str) -> str:
    c = (channel or "").strip().lower()
    if c not in {"email", "phone"}:
        raise HTTPException(status_code=400, detail="channel must be email or phone")
    return c


def _normalize_otp_purpose(purpose: str) -> str:
    p = (purpose or "").strip().lower()
    allowed = {"register", "login", "reset_password", "verify_email", "verify_phone"}
    if p not in allowed:
        raise HTTPException(status_code=400, detail="invalid otp purpose")
    return p


def _normalize_otp_target(channel: str, target: str) -> str:
    if channel == "email":
        value = _norm_email(target)
        if not value or "@" not in value:
            raise HTTPException(status_code=400, detail="invalid email target")
        return value
    value = _norm_phone(target)
    if not value or len(re.sub(r"[^0-9]", "", value)) < 8:
        raise HTTPException(status_code=400, detail="invalid phone target")
    return value


def _send_email_otp(email: str, code: str, purpose: str) -> bool:
    if not SMTP_HOST:
        return False
    subject = "CVdoor verification code"
    body = (
        f"Your CVdoor verification code is: {code}\n"
        f"Purpose: {purpose}\n"
        f"This code expires in {AUTH_OTP_TTL_SEC // 60} minutes."
    )
    msg = EmailMessage()
    msg["From"] = SMTP_FROM
    msg["To"] = email
    msg["Subject"] = subject
    msg.set_content(body)
    try:
        with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=10) as server:
            server.starttls()
            if SMTP_USER:
                server.login(SMTP_USER, SMTP_PASS)
            server.send_message(msg)
        return True
    except Exception as e:
        if DEBUG:
            print(f"[email otp failed] {e}")
        return False


def _send_sms_otp(phone: str, code: str, purpose: str) -> bool:
    if not (TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN and TWILIO_FROM_NUMBER):
        return False
    payload = urlencode({
        "To": phone,
        "From": TWILIO_FROM_NUMBER,
        "Body": f"CVdoor code {code}. Purpose: {purpose}. Expires in {AUTH_OTP_TTL_SEC // 60} minutes.",
    }).encode("utf-8")
    url = f"https://api.twilio.com/2010-04-01/Accounts/{TWILIO_ACCOUNT_SID}/Messages.json"
    req = urlrequest.Request(url, data=payload, method="POST")
    basic = base64.b64encode(f"{TWILIO_ACCOUNT_SID}:{TWILIO_AUTH_TOKEN}".encode("utf-8")).decode("ascii")
    req.add_header("Authorization", f"Basic {basic}")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urlrequest.urlopen(req, timeout=10) as resp:
            return 200 <= resp.status < 300
    except Exception as e:
        if DEBUG:
            print(f"[sms otp failed] {e}")
        return False


def _deliver_otp(channel: str, target: str, code: str, purpose: str) -> bool:
    if channel == "email":
        return _send_email_otp(target, code, purpose)
    return _send_sms_otp(target, code, purpose)


def _issue_otp(channel: str, target: str, purpose: str) -> str:
    now = int(time.time())
    code = f"{secrets.randbelow(1_000_000):06d}"
    code_hash = _password_hash(code)
    with _db_lock:
        conn = get_db()
        try:
            row = conn.execute(
                """SELECT created_at FROM auth_otps
                   WHERE channel=? AND target=? AND purpose=?
                   ORDER BY id DESC LIMIT 1""",
                (channel, target, purpose),
            ).fetchone()
            if row and (now - int(row["created_at"])) < AUTH_OTP_RESEND_COOLDOWN_SEC:
                raise HTTPException(
                    status_code=429,
                    detail=f"OTP resend too frequent. Retry in {AUTH_OTP_RESEND_COOLDOWN_SEC}s.",
                )
            conn.execute(
                """INSERT INTO auth_otps (channel, target, purpose, code_hash, expires_at, consumed_at, attempts, created_at)
                   VALUES (?, ?, ?, ?, ?, NULL, 0, ?)""",
                (channel, target, purpose, code_hash, now + AUTH_OTP_TTL_SEC, now),
            )
            conn.commit()
        finally:
            conn.close()
    return code


def _verify_otp(channel: str, target: str, purpose: str, code: str, consume: bool = True) -> bool:
    now = int(time.time())
    with _db_lock:
        conn = get_db()
        try:
            row = conn.execute(
                """SELECT * FROM auth_otps
                   WHERE channel=? AND target=? AND purpose=?
                   ORDER BY id DESC LIMIT 1""",
                (channel, target, purpose),
            ).fetchone()
            if not row:
                return False
            if row["consumed_at"] is not None or int(row["expires_at"] or 0) < now:
                return False
            attempts = int(row["attempts"] or 0)
            if attempts >= AUTH_OTP_MAX_ATTEMPTS:
                return False
            if not _password_verify(code, str(row["code_hash"] or "")):
                conn.execute("UPDATE auth_otps SET attempts=? WHERE id=?", (attempts + 1, row["id"]))
                conn.commit()
                return False
            if consume:
                conn.execute("UPDATE auth_otps SET consumed_at=? WHERE id=?", (now, row["id"]))
                conn.commit()
            return True
        finally:
            conn.close()


def _user_row_to_resp(row: Dict[str, Any]) -> AuthUserResp:
    token, exp = _issue_session_token(str(row.get("user_id") or ""))
    return AuthUserResp(
        user_id=str(row.get("user_id") or ""),
        email=row.get("email"),
        phone=row.get("phone"),
        name=str(row.get("name") or ""),
        provider=str(row.get("provider") or "password"),
        email_verified=bool(int(row.get("email_verified") or 0)),
        phone_verified=bool(int(row.get("phone_verified") or 0)),
        status=str(row.get("status") or "active"),
        session_token=token,
        expires_at=exp,
    )


def _find_user_by_email(email: str):
    if not email:
        return None
    with _db_lock:
        conn = get_db()
        try:
            return conn.execute("SELECT * FROM users WHERE email=?", (email,)).fetchone()
        finally:
            conn.close()


def _find_user_by_phone(phone: str):
    if not phone:
        return None
    with _db_lock:
        conn = get_db()
        try:
            return conn.execute("SELECT * FROM users WHERE phone=?", (phone,)).fetchone()
        finally:
            conn.close()


def _find_user_by_google_sub(sub: str):
    if not sub:
        return None
    with _db_lock:
        conn = get_db()
        try:
            return conn.execute("SELECT * FROM users WHERE google_sub=?", (sub,)).fetchone()
        finally:
            conn.close()


def _find_user_by_uid(uid: str):
    if not uid:
        return None
    with _db_lock:
        conn = get_db()
        try:
            return conn.execute("SELECT * FROM users WHERE user_id=?", (uid,)).fetchone()
        finally:
            conn.close()


def _is_registered_user(uid: str) -> bool:
    if not uid:
        return False
    return _find_user_by_uid(uid) is not None


def _is_active_user(uid: str) -> bool:
    row = _find_user_by_uid(uid)
    if not row:
        return False
    return str(row["status"] or "active") == "active"


def _verify_google_id_token(id_token_text: str) -> Dict[str, Any]:
    try:
        from google.oauth2 import id_token as _id_token
        from google.auth.transport import requests as _requests
    except Exception as e:
        raise HTTPException(status_code=503, detail=f"google auth sdk unavailable: {e}")
    try:
        info = _id_token.verify_oauth2_token(id_token_text, _requests.Request())
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid Google ID token")
    if not info.get("sub"):
        raise HTTPException(status_code=401, detail="Google token missing subject")
    return info

def _decode_token_part(encoded: str) -> bytes:
    padding = "=" * ((4 - len(encoded) % 4) % 4)
    return base64.urlsafe_b64decode((encoded + padding).encode("utf-8"))

def _verify_session_token(token: str) -> str:
    if not token or "." not in token:
        raise HTTPException(status_code=401, detail="Invalid session token")
    body, sig = token.split(".", 1)
    expected_sig = hmac.new(
        SESSION_TOKEN_SECRET.encode("utf-8"),
        body.encode("utf-8"),
        hashlib.sha256,
    ).digest()
    actual_sig = _decode_token_part(sig)
    if not hmac.compare_digest(expected_sig, actual_sig):
        raise HTTPException(status_code=401, detail="Invalid session token signature")
    payload = json.loads(_decode_token_part(body).decode("utf-8"))
    uid = str(payload.get("uid", "")).strip()
    exp = int(payload.get("exp", 0))
    if not uid:
        raise HTTPException(status_code=401, detail="Invalid session token payload")
    if exp <= int(time.time()):
        raise HTTPException(status_code=401, detail="Session token expired")
    return uid

def _extract_bearer(authorization: Optional[str]) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="Missing Authorization header")
    prefix = "bearer "
    text = authorization.strip()
    if not text.lower().startswith(prefix):
        raise HTTPException(status_code=401, detail="Authorization must be Bearer token")
    token = text[len(prefix):].strip()
    if not token:
        raise HTTPException(status_code=401, detail="Empty bearer token")
    return token

def _require_api_key(x_api_key: Optional[str]) -> None:
    if not SERVER_API_KEY:
        raise HTTPException(status_code=503, detail="Server API key is not configured")
    key = (x_api_key or "").strip()
    if key == SERVER_API_KEY:
        return
    # Web / mobile session tokens are accepted as X-API-Key value.
    # Token format: <base64payload>.<base64sig> — always contains a dot and is long.
    if "." in key and len(key) > 40:
        try:
            _verify_session_token(key)
            return
        except HTTPException:
            pass
    raise HTTPException(status_code=401, detail="Invalid API key")


def _resolve_user_id_from_api_key(x_api_key: Optional[str], fallback_user_id: Optional[str] = None) -> str:
    """Best-effort user binding for session-token calls when client omits user_id."""
    uid = (fallback_user_id or "").strip()
    if uid:
        return uid
    key = (x_api_key or "").strip()
    if "." in key and len(key) > 40:
        try:
            return _verify_session_token(key)
        except HTTPException:
            return ""
    return ""

def _resume_focus_excerpt(resume: str, max_chars: int = MAX_RESUME_CONTEXT_CHARS) -> str:
    text = (resume or "").strip()
    if len(text) <= max_chars:
        return text
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    if not lines:
        return text[:max_chars]
    if len(lines) <= RESUME_EXCERPT_HEAD_LINES + RESUME_EXCERPT_TAIL_LINES:
        return text[:max_chars]
    head = "\n".join(lines[:RESUME_EXCERPT_HEAD_LINES]).strip()
    tail = "\n".join(lines[-RESUME_EXCERPT_TAIL_LINES:]).strip()
    merged = f"{head}\n...\n{tail}".strip()
    return merged[:max_chars]

def _try_parse_json(text: str):
    try:
        return json.loads(text)
    except Exception:
        pass
    s, e = text.find("{"), text.rfind("}")
    if s != -1 and e != -1 and e > s:
        try:
            return json.loads(text[s:e+1])
        except Exception:
            pass
    return None

def _clamp(v, lo=0, hi=100):
    try:
        return max(lo, min(hi, int(v)))
    except Exception:
        return lo

def _dedup(lst, limit):
    seen, out = set(), []
    for k in (str(x).strip() for x in (lst or []) if str(x).strip()):
        kl = k.lower()
        if kl not in seen:
            out.append(k)
            seen.add(kl)
        if len(out) >= limit:
            break
    return out

def _build_dim(raw: dict) -> DimAnalysisOut:
    before = _clamp(raw.get("before", 50))
    after = _clamp(raw.get("after", 50))
    if after < before:
        after = before
    return DimAnalysisOut(
        name=str(raw.get("name", "")),
        before=before,
        after=after,
        reasons=_dedup(raw.get("reasons"), 5),
        problems=_dedup(raw.get("problems"), 5),
        suggestions=_dedup(raw.get("suggestions"), 5),
        missing_before=_dedup(raw.get("missing_before"), 10),
        added_after=_dedup(raw.get("added_after"), 10),
    )

def _parse_response(obj: dict) -> OptimizeResp:
    optimized = str(obj.get("optimized", "")).strip()
    before_total = _clamp(obj.get("before_total", 0))
    after_total  = _clamp(obj.get("after_total", 0))
    if after_total < before_total:
        after_total = before_total

    dims_before = [_clamp(x) for x in (obj.get("dims_before") or [])][:6]
    dims_after  = [_clamp(x) for x in (obj.get("dims_after")  or [])][:6]
    while len(dims_before) < 6:
        dims_before.append(before_total)
    while len(dims_after)  < 6:
        dims_after.append(after_total)
    dims_after = [max(a, b) for a, b in zip(dims_after, dims_before)]

    added_keywords = _dedup(obj.get("added_keywords"), 20)

    analysis = None
    raw_a = obj.get("analysis")
    if isinstance(raw_a, dict):
        raw_o = raw_a.get("overall") or {}
        raw_dims = raw_a.get("dimensions") or []
        raw_items = raw_o.get("suggestion_items") or []
        suggestion_items = []
        for item in raw_items:
            if not isinstance(item, dict):
                continue
            suggestion_items.append(SuggestionItemOut(
                level=str(item.get("level", "should_improve")),
                suggestion=str(item.get("suggestion", "")),
                evidence=str(item.get("evidence", "")),
                priority=_clamp(item.get("priority", 3), 1, 5),
                expected_impact=str(item.get("expected_impact", "")),
            ))
        analysis = AnalysisOut(
            overall=OverallAnalysisOut(
                summary=str(raw_o.get("summary", "")),
                strengths=_dedup(raw_o.get("strengths"), 5),
                issues=_dedup(raw_o.get("issues"), 5),
                actions=_dedup(raw_o.get("actions"), 5),
                must_fix=_dedup(raw_o.get("must_fix"), 5),
                should_improve=_dedup(raw_o.get("should_improve"), 6),
                could_optimize=_dedup(raw_o.get("could_optimize"), 6),
                data_gaps=_dedup(raw_o.get("data_gaps"), 8),
                suggestion_items=suggestion_items[:10],
            ),
            dimensions=[_build_dim(d) for d in raw_dims if isinstance(d, dict)],
        )

    return OptimizeResp(
        optimized=optimized,
        before_total=before_total,
        after_total=after_total,
        dims_before=dims_before,
        dims_after=dims_after,
        match_score=after_total,
        added_keywords=added_keywords,
        cover_letter=str(obj.get("cover_letter", "")).strip() or None,
        analysis=analysis,
    )

_BULLET_RE = re.compile(r"^\s*(?:[-*•·▪]|\d+[\).、])\s+")
_METRIC_RE = re.compile(r"(\d|%|％|x|倍|HK\$|\$|¥|人|名|个|次|小时|天|周|月|年)")
_PLACEHOLDER_RE = re.compile(
    r"\[(?:请补充|待补充|待填写|to be filled|tbd|company|position|metric|数字)[\w\s\-:：，,]*\]",
    re.IGNORECASE
)

def _lines(text: str):
    return [ln.strip() for ln in (text or "").splitlines() if ln.strip()]

def _is_bullet_line(line: str) -> bool:
    return bool(_BULLET_RE.search(line))

def _has_metric(line: str) -> bool:
    return bool(_METRIC_RE.search(line))

def _quant_coverage(text: str):
    lines = _lines(text)
    bullets = [ln for ln in lines if _is_bullet_line(ln)]
    if not bullets:
        return 0, 0
    quantified = [ln for ln in bullets if _has_metric(ln)]
    return len(quantified), len(bullets)

def _similarity(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    return difflib.SequenceMatcher(None, a, b).ratio()

def _needs_resume_rewrite(original: str, optimized: str) -> bool:
    if not optimized.strip():
        return True
    sim = _similarity(original, optimized)
    q, total = _quant_coverage(optimized)
    too_similar = sim >= 0.75
    not_quantified_enough = total >= 3 and q < max(2, total // 3)
    return too_similar or not_quantified_enough

def _is_substantive_rewrite(original: str, rewritten: str) -> bool:
    """Guardrail: reject rewrites that are too close to original text."""
    if not original.strip() or not rewritten.strip():
        return False
    sim = _similarity(original, rewritten)
    growth = (len(rewritten) - len(original)) / max(1, len(original))
    q, total = _quant_coverage(rewritten)
    quant_ok = (total < 3) or (q >= max(2, total // 3))
    # Keep this strict to avoid "looks changed but actually similar" outcomes.
    return sim < 0.68 and growth >= 0.15 and quant_ok


def _rewrite_resume_with_quantification(resume: str, jd: str, draft: str, aggressive: bool = False) -> str:
    extra_rule = ""
    if aggressive:
        extra_rule = """
8) 【强制改写】逐条重写工作经历bullet，禁止保留原句式；每条bullet至少替换80%措辞，并补充“怎么做 + 结果”。
9) 如果你发现某条bullet与原文非常相似，必须再次改写直到明显不同。"""
    prompt = f"""你是顶级简历优化专家。请对"当前优化稿"做深度改造，输出最终可投递简历正文（纯文本，不要JSON）。

改造标准（每条必须执行）：
1) 弱动词全部升级：负责→主导/统筹，参与→推动/协作完成，协助→支援/配合
2) 每条工作经历bullet结构：强动词 + 具体方法/行动 + 量化成果
3) 量化：有真实数字就用，没有就写"（请补充：xx指标数字）"，绝不编造
4) 【重要】技能/语言/证书/获奖/教育段落绝对不加量化placeholder
5) JD关键词植入：把JD中的精确词汇自然融入bullet和自我介绍段
6) 自我介绍段必须涵盖JD全部必要技能关键词
7) 最终版本比原文明显更专业，bullet内容更丰富具体，长度增加20-40%
{extra_rule}

【原始简历】
{resume}

【职位JD】
{jd}

【当前优化稿（在此基础上深化改造）】
{draft}
"""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL_PREMIUM,
            messages=[
                {"role": "system", "content": "你只输出最终简历正文，不要解释。"},
                {"role": "user", "content": prompt},
            ],
        )
        return (comp.choices[0].message.content or "").strip()
    except Exception:
        return ""


def _ensure_substantive_rewrite(resume: str, jd: str, optimized: str) -> str:
    """Two-pass rewrite: normal pass, then aggressive fallback if still too similar."""
    candidate = optimized or ""

    if _needs_resume_rewrite(resume, candidate):
        rewritten = _rewrite_resume_with_quantification(resume, jd, candidate, aggressive=False)
        if rewritten:
            candidate = rewritten

    if not _is_substantive_rewrite(resume, candidate):
        rewritten2 = _rewrite_resume_with_quantification(resume, jd, candidate, aggressive=True)
        if rewritten2:
            old_sim = _similarity(resume, candidate)
            new_sim = _similarity(resume, rewritten2)
            if new_sim < old_sim:
                candidate = rewritten2

    return candidate

def _ensure_quant_actions(analysis: Optional[AnalysisOut]) -> AnalysisOut:
    if analysis is None:
        analysis = AnalysisOut()
    actions = list(analysis.overall.actions or [])
    quant_action = "把每段经历最后一句改成量化成果：动作 + 指标 + 结果（如：将到课率从A提升到B，+C%）。"
    if not any(("量化" in a) or ("指标" in a) or ("%" in a) for a in actions):
        actions.insert(0, quant_action)
    analysis.overall.actions = _dedup(actions, 5)
    return analysis

def _build_suggestion_tiers(analysis: AnalysisOut) -> AnalysisOut:
    overall = analysis.overall
    must_fix = list(overall.must_fix or [])
    should = list(overall.should_improve or [])
    could = list(overall.could_optimize or [])
    gaps = list(overall.data_gaps or [])
    items = list(overall.suggestion_items or [])

    for issue in overall.issues[:3]:
        must_fix.append(f"必须修复：{issue}")
    for action in overall.actions:
        if "量化" in action or "指标" in action:
            must_fix.append(action)
        else:
            should.append(action)
    for d in analysis.dimensions:
        for p in (d.problems or [])[:1]:
            should.append(f"{d.name}: {p}")
        for s in (d.suggestions or [])[:1]:
            could.append(f"{d.name}: {s}")
        for miss in (d.missing_before or [])[:2]:
            gaps.append(f"缺失关键词：{miss}")

    if not must_fix:
        must_fix.append("必须修复：每段经历补齐可验证的量化成果（含指标和结果）。")

    must_fix = _dedup(must_fix, 5)
    should = _dedup(should, 6)
    could = _dedup(could, 6)
    gaps = _dedup(gaps, 8)

    if not items:
        for idx, text in enumerate(must_fix[:3]):
            items.append(SuggestionItemOut(
                level="must_fix",
                suggestion=text,
                evidence="基于当前分析中的高优先级问题与量化缺口。",
                priority=min(5, 5 - idx),
                expected_impact="提高ATS匹配稳定性与通过率。",
            ))
        for idx, text in enumerate(should[:3]):
            items.append(SuggestionItemOut(
                level="should_improve",
                suggestion=text,
                evidence="来自 overall.actions / dimensions.problems。",
                priority=max(2, 4 - idx),
                expected_impact="提升相关性与可读性。",
            ))
        for idx, text in enumerate(could[:2]):
            items.append(SuggestionItemOut(
                level="could_optimize",
                suggestion=text,
                evidence="来自维度建议与优化空间。",
                priority=max(1, 2 - idx),
                expected_impact="进一步提升竞争力与差异化。",
            ))

    overall.must_fix = must_fix
    overall.should_improve = should
    overall.could_optimize = could
    overall.data_gaps = gaps
    overall.suggestion_items = items[:10]
    overall.actions = _dedup(must_fix[:2] + should[:2] + could[:1], 5)
    return analysis


def _force_quantified_bullets(text: str, lang: str = "zh") -> str:
    placeholder = (
        "(Please provide: [specific metric])"
        if lang == "en"
        else "（请补充：xx指标数字）"
    )

    # Section headers that contain qualitative content — bullets inside should NOT get placeholders
    _SKIP_SECTION_ZH = re.compile(
        r"^\s*(?:技能|語言|语言|能力|证书|證書|資格|资格|獎項|奖项|表彰|荣誉|榮譽|"
        r"興趣|兴趣|教育|學歷|学历|志願|志愿|參考|参考|其他|profile|skills?|language|"
        r"education|certification|award|reference|interest|hobby|hobbies)",
        re.IGNORECASE,
    )

    lines = (text or "").splitlines()
    out = []
    in_skip_section = False
    for line in lines:
        stripped = line.strip()
        # Detect section header (non-bullet, non-empty, relatively short line)
        if stripped and not _BULLET_RE.search(stripped) and len(stripped) < 60:
            in_skip_section = bool(_SKIP_SECTION_ZH.search(stripped))
        if _is_bullet_line(stripped) and not _has_metric(stripped) and not in_skip_section:
            out.append(line.rstrip() + placeholder)
        else:
            out.append(line)
    return "\n".join(out).strip()


def _check_keyword_coverage(
    optimized_resume: str,
    jd_keywords: Dict[str, List[str]],
) -> Dict[str, Any]:
    """Check how many JD-required keywords appear in the optimized resume.

    Returns:
        {
          "coverage_pct": float,        # 0.0–1.0 ratio of required keywords found
          "found": [str],               # keywords present
          "missing": [str],             # required keywords still absent
          "tech_missing": [str],        # tech-stack keywords still absent
        }
    """
    text_lower = optimized_resume.lower()
    required = jd_keywords.get("required_skills", [])
    tech = jd_keywords.get("tech_stack", [])

    found, missing = [], []
    for kw in required:
        if kw.lower() in text_lower:
            found.append(kw)
        else:
            missing.append(kw)

    tech_missing = [kw for kw in tech if kw.lower() not in text_lower]

    total = len(required)
    coverage = len(found) / total if total > 0 else 1.0
    return {
        "coverage_pct": round(coverage, 2),
        "found": found,
        "missing": missing,
        "tech_missing": tech_missing,
    }


def _opt_quality_signals(original: str, optimized: str) -> Dict[str, Any]:
    sim = _similarity(original or "", optimized or "")
    growth_pct = ((len(optimized or "") - len(original or "")) / max(1, len(original or ""))) * 100.0

    quantified, bullets = _quant_coverage(optimized or "")
    quant_ratio = (quantified / bullets) if bullets > 0 else 0.0

    weak_en = ["responsible for", "participated", "assisted", "helped", "worked on"]
    weak_zh = ["负责", "参与", "协助", "跟进", "支持"]
    weak_hits = 0
    for ln in _lines(optimized or ""):
        if not _is_bullet_line(ln):
            continue
        ll = ln.lower()
        weak_hits += sum(1 for w in weak_en if w in ll)
        weak_hits += sum(1 for w in weak_zh if w in ln)

    issues: List[str] = []
    if sim >= 0.70:
        issues.append("改写相似度过高（疑似改动不够）")
    if growth_pct < 15:
        issues.append("内容增幅不足（<15%）")
    if bullets >= 3 and quant_ratio < 0.35:
        issues.append("量化覆盖偏低（工作经历bullet中量化不足）")
    if weak_hits >= 2:
        issues.append("弱动词残留过多（负责/参与/assisted等）")

    return {
        "similarity": round(sim, 4),
        "growth_pct": round(growth_pct, 2),
        "quantified_bullet_ratio": round(quant_ratio * 100.0, 2),
        "weak_verb_hits": int(weak_hits),
        "risky": bool(issues),
        "issues": issues,
    }


_PLACEHOLDER_PATTERN = re.compile(r"\[[^\]\n]{0,60}\]|_{2,10}")


def _placeholder_residue_count(text: str) -> int:
    return len(_PLACEHOLDER_PATTERN.findall(text or ""))


def _sanitize_placeholder_tokens(text: str, lang: str = "zh") -> str:
    if not text:
        return text
    marker = "(Please add verifiable metric)" if lang == "en" else "（请补充可验证指标）"
    cleaned = re.sub(r"\[[^\]\n]{0,60}\]", marker, text)
    cleaned = re.sub(r"_{2,10}", marker, cleaned)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)
    return cleaned.strip()


def _apply_output_quality_guards(
    resp: OptimizeResp,
    *,
    resume_text: str,
    jd_text: str,
    required_info: List[str],
) -> OptimizeResp:
    """Hard-stop common low-trust output patterns before returning to client."""
    residue = _placeholder_residue_count(resp.optimized or "")
    if residue > 0:
        lang = _detect_language(jd_text or resp.optimized or "")
        resp.optimized = _sanitize_placeholder_tokens(resp.optimized or "", lang=lang)
        if resp.cover_letter:
            resp.cover_letter = _sanitize_placeholder_tokens(resp.cover_letter or "", lang=lang)
        residue_after = _placeholder_residue_count(resp.optimized or "")
        extra = f"检测到 {residue} 处未填写占位符，请补齐真实数据后再导出。"
        req = _dedup(list(required_info or []) + [extra], 10)
        resp.need_more_info = True
        resp.required_info = req
        resp.after_total = min(int(resp.after_total or 0), 72)
        if resp.match_score is not None:
            resp.match_score = resp.after_total
        _record_quality_failure(
            reason="placeholder_residue",
            quality={"placeholder_residue_count": residue, "placeholder_residue_after_sanitize": residue_after},
            required_info=req,
            resume_text=resume_text,
            jd_text=jd_text,
            cover_letter=resp.cover_letter or "",
        )
    return resp


def _compute_quality_snapshot(limit: int = 200) -> Dict[str, Any]:
    with _db_lock:
        conn = get_db()
        try:
            rows = conn.execute(
                """SELECT resume_text, optimized_text, before_total, after_total
                   FROM records ORDER BY created_at DESC LIMIT ?""",
                (limit,),
            ).fetchall()
        finally:
            conn.close()
    total = len(rows)
    if total == 0:
        return {
            "total": 0,
            "risky_rate": 1.0,
            "avg_similarity": 1.0,
            "avg_score_delta": 0.0,
            "placeholder_residue_rate": 1.0,
        }
    risky = 0
    sim_sum = 0.0
    delta_sum = 0.0
    placeholder_rows = 0
    for r in rows:
        sig = _opt_quality_signals(r["resume_text"] or "", r["optimized_text"] or "")
        risky += 1 if sig["risky"] else 0
        sim_sum += float(sig["similarity"])
        delta_sum += max(0, int(r["after_total"] or 0) - int(r["before_total"] or 0))
        if _placeholder_residue_count(r["optimized_text"] or "") > 0:
            placeholder_rows += 1
    return {
        "total": total,
        "risky_rate": round(risky / total, 4),
        "avg_similarity": round(sim_sum / total, 4),
        "avg_score_delta": round(delta_sum / total, 2),
        "placeholder_residue_rate": round(placeholder_rows / total, 4),
    }


def _compute_retention_overview(days: int = 30, sample_limit: int = 1000) -> Dict[str, Any]:
    now = int(time.time())
    since = now - days * 86400
    day_7 = now - 7 * 86400

    with _db_lock:
        conn = get_db()
        try:
            users = conn.execute(
                """SELECT user_id
                   FROM records
                   WHERE created_at>=?
                   GROUP BY user_id
                   ORDER BY MAX(created_at) DESC
                   LIMIT ?""",
                (since, sample_limit),
            ).fetchall()
            active_records = conn.execute(
                "SELECT COUNT(DISTINCT user_id) as c FROM records WHERE created_at>=?",
                (day_7,),
            ).fetchone()
            active_events = conn.execute(
                "SELECT COUNT(DISTINCT user_id) as c FROM user_events WHERE created_at>=?",
                (day_7,),
            ).fetchone()
        finally:
            conn.close()

    user_ids = [str(r["user_id"]) for r in users if str(r["user_id"]).strip()]
    healths = [_compute_retention_health(uid) for uid in user_ids]

    at_risk = len([h for h in healths if h.retention_stage == "at_risk"])
    slipping = len([h for h in healths if h.retention_stage == "slipping"])
    active = len([h for h in healths if h.retention_stage == "active"])
    users_observed = len(healths)
    active_users = max(
        int((active_records["c"] if active_records else 0) or 0),
        int((active_events["c"] if active_events else 0) or 0),
    )
    retention_rate_7d = round((active_users / users_observed), 4) if users_observed else 0.0

    dropoff_signals: List[str] = []
    if users_observed:
        if len([h for h in healths if int(h.metrics.get("active_pipeline_count", 0)) == 0]) / users_observed >= 0.4:
            dropoff_signals.append("缺少持续中的Job Tracker管道（saved/applied/interview过少）")
        if len([h for h in healths if int(h.metrics.get("versions_30d", 0)) == 0]) / users_observed >= 0.4:
            dropoff_signals.append("版本对比行为不足（resume_versions过低）")
        if len([h for h in healths if int(h.metrics.get("events_7d", 0)) == 0]) / users_observed >= 0.4:
            dropoff_signals.append("近7天微动作不足（未进行段落精修/关键词融入等）")

    return {
        "window_days": days,
        "users_observed": users_observed,
        "active_users": active,
        "slipping_users": slipping,
        "at_risk_users": at_risk,
        "retention_rate_7d": retention_rate_7d,
        "reactivation_opportunity": slipping + at_risk,
        "top_dropoff_signals": _dedup(dropoff_signals, 5),
    }


def _log_reliability_event(event_type: str, severity: str, detail: Optional[Dict[str, Any]] = None) -> None:
    if not event_type:
        return
    now = int(time.time())
    payload = json.dumps(detail or {}, ensure_ascii=False)
    sev = (severity or "info").strip().lower()
    if sev not in ("info", "warning", "error"):
        sev = "info"
    with _db_lock:
        conn = get_db()
        try:
            conn.execute(
                "INSERT INTO reliability_events (event_type, severity, detail_json, created_at) VALUES (?, ?, ?, ?)",
                (event_type.strip()[:64], sev, payload, now),
            )
            conn.commit()
        finally:
            conn.close()


def _p95(values: List[float]) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = max(0, min(len(ordered) - 1, int(math.ceil(len(ordered) * 0.95)) - 1))
    return float(ordered[idx])


def _assess_ats_screening(
    optimized_resume: str,
    jd_keywords: Dict[str, List[str]],
    lang: str = "en",
) -> ATSScreeningOut:
    """Deterministic ATS pre-screen estimate.

    This acts as an honesty layer over model-generated scores: if critical ATS
    signals are weak, we explicitly mark reject/borderline with concrete fixes.
    """
    text = optimized_resume or ""
    lower = text.lower()

    cov = _check_keyword_coverage(text, jd_keywords)
    coverage = float(cov.get("coverage_pct", 0.0))
    found = cov.get("found") or []
    missing = (cov.get("missing") or []) + (cov.get("tech_missing") or [])

    quantified, bullets = _quant_coverage(text)
    quant_ratio = (quantified / bullets) if bullets > 0 else 0.0

    weak_en = ["responsible for", "participated", "assisted", "helped", "worked on"]
    weak_zh = ["负责", "参与", "协助", "跟进", "支持"]
    weak_hits = 0
    for ln in _lines(text):
        if not _is_bullet_line(ln):
            continue
        l = ln.lower()
        weak_hits += sum(1 for w in weak_en if w in l)
        weak_hits += sum(1 for w in weak_zh if w in ln)

    # Structure signal: ATS usually expects predictable sections.
    has_exp = any(k in lower for k in ["work experience", "experience", "employment", "工作经历", "工作經歷"])
    has_skills = any(k in lower for k in ["skills", "skill", "技能"])
    structure_score = 55 + (25 if has_exp else 0) + (20 if has_skills else 0)
    structure_score = _clamp(structure_score)

    keyword_score = _clamp(round(coverage * 100))
    quant_score = _clamp(round(min(1.0, quant_ratio / 0.70) * 100)) if bullets > 0 else 40
    weak_penalty = min(35, weak_hits * 6)
    clarity_score = _clamp(85 - weak_penalty)

    internal_score = _clamp(round(
        keyword_score * 0.45 + quant_score * 0.25 + structure_score * 0.15 + clarity_score * 0.15
    ))

    hard_fails: List[str] = []
    if coverage < 0.40:
        hard_fails.append("JD关键词覆盖过低（<40%）")
    if bullets >= 3 and quant_ratio < 0.25:
        hard_fails.append("工作经历量化不足（>=3条bullet但量化覆盖<25%）")
    if not has_exp:
        hard_fails.append("缺少明确的工作经历章节标题，ATS结构识别风险高")

    must_fix: List[str] = []
    if missing:
        must_fix.append("优先补齐关键词：" + "、".join(_dedup(missing, 6)))
    if bullets >= 2 and quant_ratio < 0.60:
        must_fix.append("至少60%的工作经历bullet补充量化结果（动作+指标+结果）")
    if weak_hits > 0:
        must_fix.append("替换弱动词（负责/参与/assisted/responsible for）为强动词并补充方法细节")
    if not has_skills:
        must_fix.append("增加“技能/Skills”章节，确保ATS可抽取技能词")

    if hard_fails:
        # Hard-fail means ATS risk is structurally high: be strict and honest.
        verdict = "reject"
        pass_probability = _clamp(min(45, max(8, internal_score - 18)))
    else:
        if internal_score >= 78:
            verdict = "pass"
            pass_probability = _clamp(min(95, internal_score + 6))
        elif internal_score >= 60:
            verdict = "borderline"
            pass_probability = _clamp(internal_score)
        else:
            verdict = "reject"
            pass_probability = _clamp(max(10, internal_score - 10))

    if lang == "en":
        # Keep API bilingual by translating key hard-fail labels for EN JD.
        hard_fails = [
            x.replace("JD关键词覆盖过低（<40%）", "JD keyword coverage is too low (<40%)")
             .replace("工作经历量化不足（>=3条bullet但量化覆盖<25%）", "Insufficient quantified bullets (<25% with >=3 bullets)")
             .replace("缺少明确的工作经历章节标题，ATS结构识别风险高", "Missing clear Work Experience heading (high ATS parsing risk)")
            for x in hard_fails
        ]

    if verdict == "reject" and not must_fix:
        must_fix.append(
            "先补齐JD核心关键词并重写工作经历bullet（动作+方法+量化结果），再提交ATS。"
            if lang != "en"
            else "Add core JD keywords and rewrite experience bullets into Action + Method + Quantified Result before ATS submission."
        )

    return ATSScreeningOut(
        internal_score=internal_score,
        pass_probability=pass_probability,
        verdict=verdict,
        hard_fail_reasons=_dedup(hard_fails, 5),
        must_fix=_dedup(must_fix, 6),
        signal_breakdown={
            "keyword": keyword_score,
            "quant": quant_score,
            "structure": structure_score,
            "clarity": clarity_score,
        },
        keyword_coverage_pct=round(coverage * 100, 1),
        quantified_bullet_ratio=round(quant_ratio * 100, 1),
    )


def _patch_missing_keywords(
    optimized_resume: str,
    missing_keywords: List[str],
    jd: str,
    lang: str = "en",
) -> str:
    """Ask the model to weave in any still-missing required keywords.
    Called only when coverage < 0.75 to avoid unnecessary cost.
    Non-fatal: returns original resume on any failure.
    """
    if not missing_keywords:
        return optimized_resume
    kw_list = json.dumps(missing_keywords[:10], ensure_ascii=False)
    if lang == "en":
        prompt = (
            f"The resume below is missing these ATS-required keywords: {kw_list}\n\n"
            "Weave each keyword into the resume naturally — place it in the Summary section "
            "AND in the single most relevant experience bullet. "
            "Do NOT invent facts; only add the keyword where experience genuinely supports it. "
            "If a keyword truly cannot be placed honestly, skip it. "
            "Output ONLY the updated resume text (no JSON, no explanation).\n\n"
            f"[Resume]\n{optimized_resume}\n\n"
            f"[JD excerpt for context]\n{jd[:1500]}"
        )
    else:
        prompt = (
            f"下面的简历还缺少以下ATS必要关键词：{kw_list}\n\n"
            "请将每个关键词自然地融入简历——同时放入Summary段和最相关的经历bullet中。"
            "不得捏造事实；只在经历真实支持的情况下添加关键词。"
            "如果某个关键词确实无法诚实放入，跳过它。"
            "只输出更新后的简历正文（无JSON，无解释）。\n\n"
            f"【简历】\n{optimized_resume}\n\n"
            f"【JD节选，仅供参考】\n{jd[:1500]}"
        )
    try:
        comp = _chat_completion(
            messages=[
                {"role": "system", "content": "You are a precise ATS resume editor. Output only the resume text."},
                {"role": "user", "content": prompt},
            ],
            premium=False,
            temperature=0.1,
            _endpoint="keyword_patch",
        )
        patched = (comp.choices[0].message.content or "").strip()
        # Safety: never return something drastically shorter than input
        if patched and len(patched) >= len(optimized_resume) * 0.7:
            return patched
    except Exception:
        pass
    return optimized_resume

def _cover_letter_needs_retry(text: Optional[str]) -> bool:
    t = (text or "").strip()
    if len(t) < MIN_COVER_LETTER_LENGTH:
        return True
    if _PLACEHOLDER_RE.search(t):
        return True
    lowered = t.lower()
    # 补充兜底：用于识别未被占位符正则覆盖的英文模板残留片段
    quality_risk_markers = (
        "lorem ipsum",
        "tbd",
        "placeholder",
    )
    if any(marker in lowered for marker in quality_risk_markers):
        return True
    return False


# ===== Entity extraction (JD + resume) — drives true personalization =====
#
# Without these, every cover letter falls back to "Dear Hiring Manager" and
# generic "your company". Pre-extracting entities once per request lets every
# downstream prompt reference real names, and lets the validator reject letters
# that fail to use them.

_CJK_RE = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf]")
_GENERIC_COMPANY_MARKERS = (
    "the company", "your company", "your firm", "your team",
    "your organization", "your organisation",
    "[company]", "[company name]", "[employer]", "[role]", "[position]",
    "贵公司", "贵司", "贵企业", "[公司]", "[公司名]", "[职位]",
)

def _detect_language(text: str) -> str:
    """Return 'zh' if input is predominantly CJK, else 'en'.

    Used to make the cover letter language follow the JD, not a hard-coded default.
    """
    if not text:
        return "en"
    cjk = len(_CJK_RE.findall(text))
    # JD usually short; even 8+ CJK chars is a strong signal it's Chinese.
    if cjk >= max(8, int(len(text) * 0.05)):
        return "zh"
    return "en"

def _parse_jd_entities(jd: str) -> Dict[str, Any]:
    """Extract the structured signals a recruiter would scan for in a JD."""
    if not (jd or "").strip():
        return {}
    prompt = f"""You are an information-extraction model. Read this job description
and return ONLY a JSON object with the following keys. Use empty string / empty
list when the JD does not contain that information. Do NOT invent values.

{{
  "company": "<exact company name as it appears, or ''>",
  "role_title": "<exact role title, or ''>",
  "location": "<city / region / 'remote' / ''>",
  "hiring_manager": "<name if mentioned, else ''>",
  "language": "<'zh' or 'en'>",
  "must_have_skills": ["..."],
  "nice_to_have_skills": ["..."],
  "years_required": "<e.g. '3+', '5-7', '' >",
  "responsibilities": ["1-2 short bullets capturing the main duties"]
}}

JD:
\"\"\"{jd[:4000]}\"\"\""""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
        )
        obj = _try_parse_json((comp.choices[0].message.content or "").strip()) or {}
    except Exception:
        obj = {}
    # Normalise + always provide a fallback language so downstream code never crashes.
    obj["language"] = obj.get("language") or _detect_language(jd)
    obj["must_have_skills"] = _dedup(obj.get("must_have_skills") or [], 8)
    obj["nice_to_have_skills"] = _dedup(obj.get("nice_to_have_skills") or [], 8)
    obj["responsibilities"] = _dedup(obj.get("responsibilities") or [], 4)
    return obj

def _parse_resume_entities(resume: str) -> Dict[str, Any]:
    """Extract candidate name + headline so the cover letter can sign off real."""
    if not (resume or "").strip():
        return {}
    prompt = f"""Extract candidate facts from the resume. Return ONLY JSON.
Use empty string when missing. Do NOT invent.

{{
  "candidate_name": "<full name, or ''>",
  "email": "<email, or ''>",
  "phone": "<phone, or ''>",
  "headline": "<current title or 1-line professional headline, or ''>",
  "years_experience": "<integer string or ''>",
  "top_achievements": ["3 short quantified achievement bullets"]
}}

Resume:
\"\"\"{_resume_focus_excerpt(resume)}\"\"\""""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
        )
        obj = _try_parse_json((comp.choices[0].message.content or "").strip()) or {}
    except Exception:
        obj = {}
    obj["top_achievements"] = _dedup(obj.get("top_achievements") or [], 5)
    return obj

def _validate_personalization(letter: str, jd_entities: Dict[str, Any]) -> List[str]:
    """Return human-readable issues that should trigger a rewrite."""
    if not letter:
        return ["letter is empty"]
    issues: List[str] = []
    lower = letter.lower()
    company = (jd_entities.get("company") or "").strip()
    role = (jd_entities.get("role_title") or "").strip()
    # 1. Generic-firm markers — kill them.
    for marker in _GENERIC_COMPANY_MARKERS:
        if marker in lower:
            issues.append(f"generic placeholder present: '{marker}'")
            break
    # 2. If we extracted a real company name, it MUST appear.
    if company and company.lower() not in lower:
        issues.append(f"missing real company name '{company}'")
    # 3. Same for role title (best-effort — only fail if both company and role missing)
    if role and role.lower() not in lower and not issues:
        issues.append(f"missing role title '{role}'")
    return issues


def _build_context_block(
    industry: Optional[str],
    seniority: Optional[str],
    target_role: Optional[str],
    region: Optional[str],
    lang: str,
) -> str:
    """
    Render a short '候選人定位 / Candidate Targeting' block that conditions the AI on
    seniority + industry + target role. Without this, gpt-4o-mini defaults to a generic
    mid-level optimization, which damages output quality for both juniors and seniors.
    """
    industry = (industry or "").strip()
    seniority = (seniority or "").strip()
    target_role = (target_role or "").strip()
    region = (region or "").strip()
    if not any([industry, seniority, target_role, region]):
        return ""
    if lang == "en":
        lines = ["[Candidate Targeting]"]
        if seniority:    lines.append(f"• Seniority level: {seniority} — calibrate responsibilities, depth, and quantification accordingly.")
        if target_role:  lines.append(f"• Target role: {target_role}")
        if industry:     lines.append(f"• Target industry: {industry}")
        if region:       lines.append(f"• Target region: {region} (use locally preferred terminology)")
        lines.append("Match seniority strictly: do not inflate junior achievements into senior accomplishments, nor understate senior leadership impact.")
    else:
        lines = ["【候选人定位】"]
        if seniority:    lines.append(f"• 资历级别：{seniority} —— 请按此级别的职责范围、主责压力和量化深度调校描述。")
        if target_role:  lines.append(f"• 目标职位：{target_role}")
        if industry:     lines.append(f"• 目标行业：{industry}")
        if region:       lines.append(f"• 目标地区：{region}（请采用本地常用表达习惯）")
        lines.append("请严格匹配级别与职责：不要把初级经验护闸成资深成就，也不要弱化资深背景的领导力与影响力。")
    return "\n".join(lines) + "\n\n"


# ── JD Keyword Pre-Extraction ─────────────────────────────────────────────────
# Called once before the main optimize prompt. Returns a structured keyword list
# so the main prompt can reference exact terms rather than letting the model guess.
# Uses the fast model (cheap: ~$0.0003) to avoid adding latency with premium model.

def _extract_jd_keywords(jd: str, lang: str = "en") -> Dict[str, List[str]]:
    """Extract structured skill keywords from a JD. Non-fatal: returns empty dict on failure."""
    if lang == "en":
        prompt = (
            'You are an ATS keyword analyst. Extract keywords from the job description below.\n'
            'Output ONLY JSON with exactly these keys:\n'
            '{\n'
            '  "required_skills": ["exact phrases marked as required/must-have"],\n'
            '  "preferred_skills": ["exact phrases marked as preferred/nice-to-have"],\n'
            '  "tech_stack": ["specific tools, languages, frameworks, platforms"],\n'
            '  "soft_skills": ["interpersonal and leadership phrases from JD"],\n'
            '  "role_keywords": ["title-level keywords, domain terms, industry jargon"]\n'
            '}\n\n'
            'Rules:\n'
            '- Copy phrases VERBATIM from the JD; do not paraphrase.\n'
            '- Each list: max 8 items, ordered by importance.\n'
            '- If a section has nothing, use [].\n\n'
            f'[Job Description]\n{jd[:3000]}'
        )
    else:
        prompt = (
            '你是ATS关键词分析师。从下面的JD中提取关键词。\n'
            '只输出JSON，包含以下字段：\n'
            '{\n'
            '  "required_skills": ["JD中明确要求/必须具备的技能短语（原文照录）"],\n'
            '  "preferred_skills": ["JD中优先/加分项技能短语（原文照录）"],\n'
            '  "tech_stack": ["具体工具、编程语言、框架、平台名称"],\n'
            '  "soft_skills": ["JD中提及的软技能短语"],\n'
            '  "role_keywords": ["职位级别关键词、领域术语、行业词汇"]\n'
            '}\n\n'
            '规则：\n'
            '- 短语必须与JD原文一致，不要改写。\n'
            '- 每个列表最多8项，按重要程度排序。\n'
            '- 没有内容的列表用[]。\n\n'
            f'【JD】\n{jd[:3000]}'
        )
    try:
        comp = _chat_completion(
            messages=[{"role": "user", "content": prompt}],
            premium=False,
            temperature=0.0,
            response_format={"type": "json_object"},
            _endpoint="jd_keywords",
        )
        raw = (comp.choices[0].message.content or "").strip()
        result = _try_parse_json(raw) or {}
        # Normalise — ensure all expected keys exist as lists
        for key in ("required_skills", "preferred_skills", "tech_stack", "soft_skills", "role_keywords"):
            if not isinstance(result.get(key), list):
                result[key] = []
        return result
    except Exception:
        return {}


def _build_keyword_injection_block(kw: Dict[str, List[str]], lang: str) -> str:
    """Format extracted JD keywords as a clear instruction block for the main prompt."""
    if not kw:
        return ""
    required = kw.get("required_skills", [])
    preferred = kw.get("preferred_skills", [])
    tech = kw.get("tech_stack", [])
    soft = kw.get("soft_skills", [])
    role = kw.get("role_keywords", [])
    if not any([required, preferred, tech, soft, role]):
        return ""

    if lang == "en":
        lines = ["\n[ATS Keyword Targets — use EXACT phrases, no synonyms]"]
        if required:
            lines.append(f"MUST place all of these (required skills): {', '.join(required)}")
        if preferred:
            lines.append(f"Place as many as truthfully possible (preferred): {', '.join(preferred)}")
        if tech:
            lines.append(f"Technical stack to include: {', '.join(tech)}")
        if soft:
            lines.append(f"Soft skill phrases to weave in: {', '.join(soft)}")
        if role:
            lines.append(f"Role/domain keywords: {', '.join(role)}")
        lines.append("Placement rule: each item above must appear in Summary AND in the most relevant experience bullet.\n")
    else:
        lines = ["\n【ATS关键词目标 — 必须原文照用，不得改写】"]
        if required:
            lines.append(f"必须全部嵌入（必要技能）：{', '.join(required)}")
        if preferred:
            lines.append(f"尽量嵌入（加分项）：{', '.join(preferred)}")
        if tech:
            lines.append(f"技术栈关键词：{', '.join(tech)}")
        if soft:
            lines.append(f"软技能短语：{', '.join(soft)}")
        if role:
            lines.append(f"职位/领域关键词：{', '.join(role)}")
        lines.append("放置规则：以上每个关键词必须同时出现在Summary段和最相关的经历bullet中。\n")
    return "\n".join(lines)


def _call_gpt(
    resume: str,
    jd: str,
    seniority: Optional[str] = None,
    industry: Optional[str] = None,
    target_role: Optional[str] = None,
    region: Optional[str] = None,
) -> dict:
    # Select prompt language based on JD language so analysis output matches user's language.
    lang = _detect_language(jd)
    system_prompt = SYSTEM_PROMPT_EN if lang == "en" else SYSTEM_PROMPT
    context_block = _build_context_block(industry, seniority, target_role, region, lang)

    # Pre-extract JD keywords for explicit targeting (cheap mini-model call)
    jd_keywords = _extract_jd_keywords(jd, lang)
    keyword_block = _build_keyword_injection_block(jd_keywords, lang)

    user_msg = (context_block or "") + _build_user_msg(resume, jd) + keyword_block
    for use_json_mode in (True, False):
        kwargs = dict(
            model=OPENAI_MODEL_PREMIUM,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user",   "content": user_msg},
            ],
        )
        if use_json_mode:
            kwargs["response_format"] = {"type": "json_object"}
        try:
            comp = client.chat.completions.create(**kwargs)
            raw = (comp.choices[0].message.content or "").strip()
            if DEBUG:
                # PII safety: never print full GPT output (may contain user resume / cover letter
                # with names + contact info). Print length + first 60 chars only.
                snippet = (raw or "")[:60].replace("\n", " ")
                print(f"[gpt ok json_mode={use_json_mode}] {len(raw or '')} chars  preview={snippet!r}")
            obj = _try_parse_json(raw)
            if obj:
                return obj
        except Exception as e:
            if DEBUG:
                print(f"GPT call error json_mode={use_json_mode}: {e}")
    return {}

def _extract_cover_letter_brief(
    resume: str,
    jd: str,
    style: str,
    industry: Optional[str] = None,
    seniority: Optional[str] = None,
    region: Optional[str] = None,
    tone: Optional[str] = None,
) -> dict:
    normalized_style = _normalize_cover_letter_style(style)
    normalized_tone = _normalize_cover_letter_tone(tone)
    prompt = f"""你是资深求职策略顾问。请只输出 JSON，字段如下：
{{
  "fit_summary":"候选人与岗位匹配总论",
  "jd_focus":["JD关键要求1","JD关键要求2","JD关键要求3"],
  "achievement_evidence":["可写入求职信的经历证据1","证据2","证据3"],
  "missing_data":["缺失但需要用户补充的数据1","数据2"],
  "positioning":"候选人定位与价值主张"
}}

【简历】{_resume_focus_excerpt(resume)}
【JD】{jd}
【风格】{normalized_style}
【语气强度】{normalized_tone}
【行业】{(industry or "").strip() or "unknown"}
【资历】{(seniority or "").strip() or "unknown"}
【地区】{(region or "").strip() or "unknown"}"""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
        )
        raw = (comp.choices[0].message.content or "").strip()
        return _try_parse_json(raw) or {}
    except Exception:
        return {}

def _draft_cover_letter_from_brief(
    resume: str,
    jd: str,
    brief: dict,
    style: str,
    tone: Optional[str] = None,
    jd_entities: Optional[Dict[str, Any]] = None,
    resume_entities: Optional[Dict[str, Any]] = None,
    variant: str = "standard",  # 'standard' | 'concise' | 'detailed'
) -> str:
    normalized_style = _normalize_cover_letter_style(style)
    normalized_tone = _normalize_cover_letter_tone(tone)
    je = jd_entities or {}
    re_ = resume_entities or {}
    # Use character-based detection as ground truth — GPT entity extraction
    # sometimes labels Chinese JDs as 'en' when the text contains English terms.
    language = _detect_language(jd)
    company = (je.get("company") or "").strip()
    role = (je.get("role_title") or "").strip()
    location = (je.get("location") or "").strip()
    hiring_manager = (je.get("hiring_manager") or "").strip()
    candidate_name = (re_.get("candidate_name") or "").strip()
    headline = (re_.get("headline") or "").strip()
    must_have = ", ".join(je.get("must_have_skills") or [])

    # Variant defines length + angle; all are still personalized & real.
    if variant == "concise":
        length_en = "180-220 words, 3 short paragraphs."
        length_zh = "中文 280-340 字，分 3 个短段落。"
        angle = "Open with the single strongest achievement that maps to the role."
    elif variant == "detailed":
        length_en = "320-400 words, 4 paragraphs (hook, fit, proof, close)."
        length_zh = "中文 480-600 字，分 4 个段落（开场、契合、证据、收尾）。"
        angle = "Bring 3 quantified achievements and a forward-looking 'first 90 days' line."
    else:
        length_en = "250-320 words, 3-4 paragraphs."
        length_zh = "中文 380-460 字，分 3-4 个段落。"
        angle = "Balance fit narrative with 2 quantified achievements that mirror JD must-haves."

    if language == "zh":
        salutation = (
            f"尊敬的 {hiring_manager}：" if hiring_manager
            else (f"尊敬的 {company} 招聘团队：" if company else "尊敬的招聘团队：")
        )
        sign_off = f"此致\n敬礼\n{candidate_name}" if candidate_name else "此致\n敬礼"
        prompt = f"""你是资深中文求职信撰稿人。请输出一封可直接寄出的中文求职信。
长度：{length_zh}
规则：
- 必须以「{salutation}」开头，以「{sign_off}」结尾。
- 必须在正文中出现公司名「{company}」与职位「{role}」原文，不可改写为「贵公司」。
- 不允许出现「贵公司」「贵司」「[公司]」「[公司名]」「[职位]」等占位符。
- 必须引用简历中的 2-3 个可量化成果（带具体数字），不可凭空编造数字。
- {angle}
【候选人】{candidate_name or '-'} / {headline}
【公司】{company}  【职位】{role}  【地点】{location}
【必备技能】{must_have}
【风格】{normalized_style}  【语气】{normalized_tone}

【简历摘要】
{_resume_focus_excerpt(resume)}

【JD】
{jd}

【匹配提炼(JSON)】
{json.dumps(brief, ensure_ascii=False)}

只输出信件正文，不要 markdown 代码块、不要说明。"""
    else:
        salutation = (
            f"Dear {hiring_manager}," if hiring_manager
            else (f"Dear Hiring Team at {company}," if company else "Dear Hiring Team,")
        )
        sign_off = f"Sincerely,\n{candidate_name}" if candidate_name else "Sincerely,"
        prompt = f"""You are an expert cover-letter writer. Output a single send-ready cover letter.
Length: {length_en}
Rules:
- Open with EXACTLY: '{salutation}'.
- Close with EXACTLY: '{sign_off}'.
- MUST mention the literal company name '{company}' and the role title '{role}' in the body.
- DO NOT use placeholders like 'the company', 'your firm', 'your organization', '[Company]'.
- MUST cite 2-3 quantified achievements taken from the resume; never fabricate numbers.
- {angle}
[Candidate] {candidate_name or '-'} / {headline}
[Company] {company}   [Role] {role}   [Location] {location}
[Must-have skills] {must_have}
[Style] {normalized_style}   [Tone] {normalized_tone}

[Resume excerpt]
{_resume_focus_excerpt(resume)}

[JD]
{jd}

[Match brief JSON]
{json.dumps(brief, ensure_ascii=False)}

Output only the cover letter body, no markdown fencing, no commentary."""
    try:
        comp = _chat_completion(
            messages=[{"role": "user", "content": prompt}],
            premium=True,
            temperature=0.7,
            max_tokens=900,
        )
        return (comp.choices[0].message.content or "").strip()
    except Exception:
        return ""

def _evaluate_cover_letter_quality(letter: str, resume: str, jd: str) -> Dict[str, Any]:
    fallback = {
        "relevance": 70,
        "quantification": 70 if re.search(r"\d|%", letter or "") else 45,
        "specificity": 68,
        "structure": 75 if ("dear" in (letter or "").lower() and "sincerely" in (letter or "").lower()) else 55,
        "language": 72,
        "overall": 0,
        "feedback": [],
    }
    try:
        prompt = f"""请评估下面求职信质量并只输出JSON：
{{
  "relevance":0-100,
  "quantification":0-100,
  "specificity":0-100,
  "structure":0-100,
  "language":0-100,
  "overall":0-100,
  "feedback":["改进建议1","改进建议2"]
}}
评估维度：与JD相关性、量化成就、具体性、结构完整性、语言自然度。

【JD】{jd}
【简历摘要】{_resume_focus_excerpt(resume, 1600)}
【求职信】{letter}
"""
        comp = _chat_completion(
            messages=[{"role": "user", "content": prompt}],
            response_format={"type": "json_object"},
            temperature=0.0,
        )
        raw = (comp.choices[0].message.content or "").strip()
        obj = _try_parse_json(raw) or {}
        result = {
            "relevance": _clamp(obj.get("relevance", fallback["relevance"])),
            "quantification": _clamp(obj.get("quantification", fallback["quantification"])),
            "specificity": _clamp(obj.get("specificity", fallback["specificity"])),
            "structure": _clamp(obj.get("structure", fallback["structure"])),
            "language": _clamp(obj.get("language", fallback["language"])),
            "overall": _clamp(obj.get("overall", 0)),
            "feedback": _dedup(obj.get("feedback"), 4),
        }
        if result["overall"] <= 0:
            dims = [result["relevance"], result["quantification"], result["specificity"], result["structure"], result["language"]]
            result["overall"] = int(sum(dims) / len(dims))
        return result
    except Exception:
        dims = [fallback["relevance"], fallback["quantification"], fallback["specificity"], fallback["structure"], fallback["language"]]
        fallback["overall"] = int(sum(dims) / len(dims))
        fallback["feedback"] = ["增加与JD一一映射的技能证据。", "补充可验证的量化成果。"]
        return fallback

def _rewrite_cover_letter_once(letter: str, resume: str, jd: str, feedback: List[str], style: str, tone: Optional[str] = None) -> str:
    if not letter.strip():
        return ""
    lang = _detect_language(jd)
    if lang == "zh":
        prompt = f"""请基于反馈重写以下求职信（中文，380-460字），保持真实、具体、结构完整。
规则：
- 必须全程使用中文，不得出现英文段落或英文称谓（如 Dear / Sincerely）。
- 必须包含公司名（原文）和职位名（原文），不得用"贵公司"等占位符代替。
- 必须引用简历中 2-3 个可量化成果，不可编造数字。
反馈：
{json.dumps(feedback, ensure_ascii=False)}

【JD】{jd}
【简历摘要】{_resume_focus_excerpt(resume)}
【原始求职信】{letter}
【风格】{_normalize_cover_letter_style(style)}
【语气】{_normalize_cover_letter_tone(tone)}
只输出重写后的求职信正文。"""
    else:
        prompt = f"""Rewrite the cover letter below based on the feedback (250-320 words). Keep it genuine, specific, and well-structured.
Feedback:
{json.dumps(feedback, ensure_ascii=False)}

[JD]{jd}
[Resume excerpt]{_resume_focus_excerpt(resume)}
[Original letter]{letter}
[Style]{_normalize_cover_letter_style(style)}
[Tone]{_normalize_cover_letter_tone(tone)}
Output only the rewritten letter body."""
    try:
        comp = _chat_completion(
            messages=[{"role": "user", "content": prompt}],
            premium=True,
            temperature=0.6,
            max_tokens=900,
        )
        return (comp.choices[0].message.content or "").strip()
    except Exception:
        return ""

def _generate_cover_letter_only(
    resume: str,
    jd: str,
    style: str = "professional",
    industry: Optional[str] = None,
    seniority: Optional[str] = None,
    region: Optional[str] = None,
    tone: Optional[str] = None,
    variant: str = "standard",
    jd_entities: Optional[Dict[str, Any]] = None,
    resume_entities: Optional[Dict[str, Any]] = None,
) -> tuple[str, Dict[str, Any]]:
    """Two-stage generation, now anchored on extracted JD + resume entities so
    the output is actually personalized (real company name, real role, matched
    language, real signature). Falls back gracefully when extraction fails.
    """
    normalized_style = _normalize_cover_letter_style(style)
    normalized_tone = _normalize_cover_letter_tone(tone)
    # Reuse pre-computed entities if caller already extracted them (variants).
    if jd_entities is None:
        jd_entities = _parse_jd_entities(jd)
    if resume_entities is None:
        resume_entities = _parse_resume_entities(resume)
    brief = _extract_cover_letter_brief(
        resume=resume,
        jd=jd,
        style=normalized_style,
        industry=industry,
        seniority=seniority,
        region=region,
        tone=normalized_tone,
    )
    cover_letter = _draft_cover_letter_from_brief(
        resume=resume,
        jd=jd,
        brief=brief,
        style=normalized_style,
        tone=normalized_tone,
        jd_entities=jd_entities,
        resume_entities=resume_entities,
        variant=variant,
    )
    quality = _evaluate_cover_letter_quality(cover_letter, resume, jd)
    # Personalization is a HARD requirement: a letter that doesn't reference the
    # real company / role is worthless regardless of GPT's self-rating.
    personalization_issues = _validate_personalization(cover_letter, jd_entities)
    needs_rewrite = (
        _cover_letter_needs_retry(cover_letter)
        or quality.get("overall", 0) < MIN_COVER_LETTER_QUALITY_SCORE
        or bool(personalization_issues)
    )
    if needs_rewrite:
        feedback = list(quality.get("feedback") or []) + personalization_issues
        if not feedback:
            feedback = ["请增强与 JD 要求的逐项映射。", "请补充量化成果。"]
        rewritten = _rewrite_cover_letter_once(
            letter=cover_letter or "(empty draft)",
            resume=resume,
            jd=jd,
            feedback=feedback,
            style=normalized_style,
            tone=normalized_tone,
        )
        if len(rewritten or "") >= MIN_COVER_LETTER_LENGTH:
            cover_letter = rewritten
            quality = _evaluate_cover_letter_quality(cover_letter, resume, jd)
    quality["jd_entities"] = jd_entities
    quality["resume_entities"] = resume_entities
    quality["personalization_issues"] = _validate_personalization(cover_letter, jd_entities)
    quality["variant"] = variant
    if DEBUG and cover_letter:
        # PII safety: don't print cover letter body (contains user name + experience).
        # Length + quality score is enough for debugging.
        print(f"[cover_letter generated] {len(cover_letter)} chars  quality={quality.get('overall', 0)}  variant={variant}")
    return cover_letter, quality

def _extract_required_info(analysis: Optional[AnalysisOut]) -> List[str]:
    if not analysis:
        return []
    gaps = list((analysis.overall.data_gaps or []))
    for d in analysis.dimensions:
        for miss in (d.missing_before or []):
            gaps.append(f"{d.name}: {miss}")
    return _dedup(gaps, 10)

def _record_quality_failure(
    reason: str,
    quality: Dict[str, Any],
    required_info: List[str],
    resume_text: str,
    jd_text: str,
    cover_letter: str,
) -> None:
    now = int(time.time())
    with _db_lock:
        conn = get_db()
        conn.execute(
            """INSERT INTO quality_failures
               (reason, quality_json, required_info, resume_excerpt, jd_excerpt, cover_letter, created_at)
               VALUES (?,?,?,?,?,?,?)""",
            (
                reason,
                json.dumps(quality or {}, ensure_ascii=False),
                json.dumps(required_info or [], ensure_ascii=False),
                _resume_focus_excerpt(resume_text, 1200),
                _resume_focus_excerpt(jd_text, 1200),
                (cover_letter or "")[:2400],
                now,
            ),
        )
        conn.commit()
        conn.close()

# ===== Records helpers =====
def _save_record(user_id: str, resume: str, jd: str, resp: OptimizeResp):
    now = int(time.time())
    analysis_json = resp.analysis.model_dump_json() if resp.analysis else None
    with _db_lock:
        conn = get_db()
        record_id = conn.insert_get_id(
            """INSERT INTO records
               (user_id,resume_text,jd_text,optimized_text,
                before_total,after_total,dims_before,dims_after,
                analysis_json,created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?)""",
            (user_id, resume, jd, resp.optimized,
             resp.before_total, resp.after_total,
             json.dumps(resp.dims_before), json.dumps(resp.dims_after),
             analysis_json, now)
        )
        conn.commit()
        conn.close()
    return record_id, now

def _row_to_record(row) -> RecordOut:
    analysis = None
    if row["analysis_json"]:
        try:
            analysis = AnalysisOut.model_validate_json(row["analysis_json"])
        except Exception:
            pass
    return RecordOut(
        id=row["id"],
        user_id=row["user_id"],
        created_at=row["created_at"],
        resume_text=row["resume_text"],
        jd_text=row["jd_text"],
        optimized_text=row["optimized_text"],
        before_total=row["before_total"],
        after_total=row["after_total"],
        dims_before=json.loads(row["dims_before"] or "[]"),
        dims_after=json.loads(row["dims_after"] or "[]"),
        analysis=analysis,
    )

# ── Iterative-improvement IO models ────────────────────────────────────────────
# These power section-level regeneration and post-fill placeholder refinement,
# closing the two biggest UX gaps vs. Teal/Rezi/Kickresume.

class SectionOptimizeReq(BaseModel):
    """Re-optimize a single paragraph / bullet block without re-running the whole resume."""
    section_text: str = Field(..., min_length=1, max_length=8_000)
    jd_text:      str = Field(..., min_length=1, max_length=_MAX_JD_BYTES if False else 20_000)
    full_resume:  Optional[str] = Field(None, max_length=50_000)
    instructions: Optional[str] = Field(None, max_length=500)   # e.g. "make it more quantitative"
    seniority:    Optional[str] = Field(None, max_length=64)
    industry:     Optional[str] = Field(None, max_length=128)
    user_id:      Optional[str] = Field(None, max_length=128)

class SectionOptimizeResp(BaseModel):
    improved_section: str
    changes_summary:  str = ""

class RefinePlaceholdersReq(BaseModel):
    """After the user fills in real numbers, ask AI to weave them into natural language."""
    resume_text: str = Field(..., min_length=1, max_length=50_000)
    jd_text:     str = Field(..., min_length=1, max_length=20_000)
    fills: Dict[str, str] = Field(default_factory=dict)
    seniority:   Optional[str] = Field(None, max_length=64)
    industry:    Optional[str] = Field(None, max_length=128)
    user_id:     Optional[str] = Field(None, max_length=128)

class RefinePlaceholdersResp(BaseModel):
    refined_resume: str
    integrated_count: int

# ===== Routes =====
# ── Input size limits (prevents prompt-injection via huge payloads & runaway cost) ──
_MAX_RESUME_BYTES = 40_000   # ~30 pages of text; generous for any real resume
_MAX_JD_BYTES     = 20_000   # ~10 pages

def _validate_input_sizes(resume: str, jd: str) -> None:
    if len(resume.encode()) > _MAX_RESUME_BYTES:
        raise HTTPException(status_code=413, detail=f"resume_text too large (max {_MAX_RESUME_BYTES//1000}KB). Please trim and retry.")
    if len(jd.encode()) > _MAX_JD_BYTES:
        raise HTTPException(status_code=413, detail=f"jd_text too large (max {_MAX_JD_BYTES//1000}KB). Please trim and retry.")

@app.post("/v1/optimize", response_model=OptimizeResp)
def optimize(body: OptimizeReq, x_api_key: Optional[str] = Header(None)):
    started_at = time.time()
    _require_api_key(x_api_key)
    if not (body.user_id or "").strip():
        resolved_uid = _resolve_user_id_from_api_key(x_api_key, body.user_id)
        if resolved_uid:
            body.user_id = resolved_uid
    uid = (body.user_id or "").strip()
    if not uid or _find_user_by_uid(uid) is None:
        raise HTTPException(status_code=401, detail="login required: please sign in to optimize")
    body.user_id = uid
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text and jd_text are required")
    _validate_input_sizes(body.resume_text, body.jd_text)
    # Rate limit per user_id (falls back to a global bucket if anonymous).
    rl_key = f"optimize:{body.user_id or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_OPTIMIZE_PER_HOUR)
    _enforce_free_daily_quota(body.user_id or "")
    try:
        obj = _call_gpt(
            body.resume_text, body.jd_text,
            seniority=body.seniority, industry=body.industry,
            region=body.region,
        )
        if not obj:
            raise HTTPException(status_code=502, detail="AI service returned an empty response. Please retry.")

        resp = _parse_response(obj)
        rewritten = _ensure_substantive_rewrite(body.resume_text, body.jd_text, resp.optimized)
        if rewritten and rewritten.strip() and rewritten.strip() != (resp.optimized or "").strip():
            resp.optimized = rewritten
            resp.after_total = max(resp.after_total, min(100, resp.before_total + 8))
            resp.dims_after = [max(a, min(100, b + 5)) for a, b in zip(resp.dims_after, resp.dims_before)]

        lang = _detect_language(body.jd_text)
        resp.optimized = _force_quantified_bullets(resp.optimized, lang=lang)

        # Post-optimization keyword coverage check:
        # If required keywords are still missing after the main pass, do a cheap targeted patch.
        jd_kw = _extract_jd_keywords(body.jd_text, lang)
        coverage = _check_keyword_coverage(resp.optimized, jd_kw)
        if coverage["coverage_pct"] < 0.75 and (coverage["missing"] or coverage["tech_missing"]):
            patched = _patch_missing_keywords(
                resp.optimized,
                (coverage["missing"] + coverage["tech_missing"])[:8],
                body.jd_text,
                lang=lang,
            )
            if patched != resp.optimized:
                resp.optimized = patched
                resp.after_total = min(100, resp.after_total + 3)

        ats_screening = _assess_ats_screening(resp.optimized, jd_kw, lang)
        resp.ats_screening = ats_screening
        # Honesty calibration: prevent model self-score from being unrealistically high
        # when ATS hard signals are weak.
        if ats_screening.internal_score + 6 < resp.after_total:
            resp.after_total = max(resp.before_total, ats_screening.internal_score + 2)
            resp.match_score = resp.after_total
        resp.match_score = resp.after_total

        resp.analysis = _build_suggestion_tiers(_ensure_quant_actions(resp.analysis))
        if resp.analysis and resp.analysis.overall:
            resp.analysis.overall.must_fix = _dedup(
                list(resp.analysis.overall.must_fix or []) + list(ats_screening.must_fix or []),
                8,
            )
            if ats_screening.hard_fail_reasons:
                resp.analysis.overall.issues = _dedup(
                    list(resp.analysis.overall.issues or []) + list(ats_screening.hard_fail_reasons),
                    8,
                )
        if ats_screening.verdict in ("reject", "borderline"):
            _record_quality_failure(
                reason=f"ats_{ats_screening.verdict}",
                quality={
                    "internal_score": ats_screening.internal_score,
                    "pass_probability": ats_screening.pass_probability,
                    "signal_breakdown": ats_screening.signal_breakdown,
                    "keyword_coverage_pct": ats_screening.keyword_coverage_pct,
                    "quantified_bullet_ratio": ats_screening.quantified_bullet_ratio,
                },
                required_info=(ats_screening.must_fix or []) + (ats_screening.hard_fail_reasons or []),
                resume_text=body.resume_text,
                jd_text=body.jd_text,
                cover_letter=resp.cover_letter or "",
            )

        # 补齐 Cover Letter：优先沿用主调用结果，缺失或质量不足时才补调一次
        # 仅当 _call_gpt 主调用结果明显缺失或存在模板化/占位痕迹时，才重新生成，避免覆盖已生成的高质量版本。
        needs_cover_letter_retry = _cover_letter_needs_retry(resp.cover_letter)
        if needs_cover_letter_retry:
            try:
                cover_letter, quality = _generate_cover_letter_only(
                    resume=resp.optimized,
                    jd=body.jd_text,
                    style=body.style or "professional",
                    industry=body.industry,
                    seniority=body.seniority,
                    region=body.region,
                    tone=body.tone,
                )
                if len(cover_letter) >= MIN_COVER_LETTER_LENGTH:
                    resp.cover_letter = cover_letter
                    resp.cover_letter_quality = quality
            except Exception as e:
                if DEBUG:
                    print(f"Cover letter generation during optimize failed: {e}")
                # 不中断主流程，Cover Letter 生成失败不影响简历优化结果
        else:
            resp.cover_letter_quality = _evaluate_cover_letter_quality(
                resp.cover_letter or "",
                resp.optimized,
                body.jd_text,
            )

        required_info = _extract_required_info(resp.analysis)
        quality_overall = int((resp.cover_letter_quality or {}).get("overall", 0))
        if _cover_letter_needs_retry(resp.cover_letter) or quality_overall < MIN_COVER_LETTER_QUALITY_SCORE:
            resp.cover_letter_status = "need_more_info"
            resp.need_more_info = True
            resp.required_info = required_info
            _record_quality_failure(
                reason="optimize_low_quality",
                quality=resp.cover_letter_quality or {},
                required_info=required_info,
                resume_text=body.resume_text,
                jd_text=body.jd_text,
                cover_letter=resp.cover_letter or "",
            )
        else:
            resp.cover_letter_status = "ok"
            resp.required_info = required_info

        resp = _apply_output_quality_guards(
            resp,
            resume_text=body.resume_text,
            jd_text=body.jd_text,
            required_info=list(resp.required_info or required_info),
        )

        resp.score_delta = max(0, int(resp.after_total) - int(resp.before_total))
        resp.value_proof = _build_value_proof(resp, _detect_language(body.jd_text))

        if body.user_id and body.user_id.strip():
            user_id = body.user_id.strip()
            record_id, created_at = _save_record(
                user_id, body.resume_text, body.jd_text, resp
            )
            resp.record_id = record_id
            resp.created_at = created_at
            token, exp = _issue_session_token(user_id)
            resp.session_token = token
            resp.session_expires_at = exp
            _log_user_event(
                user_id,
                "optimize_completed",
                {
                    "score_delta": int(resp.score_delta or 0),
                    "ats_verdict": (resp.ats_screening.verdict if resp.ats_screening else ""),
                },
            )

        resp = _apply_trial_preview_watermark(resp, (body.user_id or "").strip())

        _log_reliability_event(
            "optimize_sync_success",
            "info",
            {
                "duration_ms": int((time.time() - started_at) * 1000),
                "user_id": (body.user_id or "")[:64],
                "score_delta": int(resp.score_delta or 0),
                "need_more_info": bool(resp.need_more_info),
            },
        )

        return resp

    except HTTPException:
        _log_reliability_event(
            "optimize_sync_http_error",
            "warning",
            {
                "duration_ms": int((time.time() - started_at) * 1000),
                "user_id": (body.user_id or "")[:64],
            },
        )
        raise
    except Exception as e:
        print("ERROR:", repr(e))
        traceback.print_exc()
        _log_reliability_event(
            "optimize_sync_exception",
            "error",
            {
                "duration_ms": int((time.time() - started_at) * 1000),
                "user_id": (body.user_id or "")[:64],
                "error": str(e)[:300],
            },
        )
        raise HTTPException(status_code=500, detail=f"AI 优化失败：{e}")


# ── Async optimize job store — DB-backed, survives server restarts ────────────
# Each row in the `jobs` table: job_id PK, status, result_json, error, timestamps.
# An in-memory dict caches hot jobs to avoid hitting SQLite on every poll.

JOB_TTL_SECONDS = 600  # rows older than this are pruned

# Hot cache: populated on write, checked first on status reads.
_jobs_cache: Dict[str, Dict[str, Any]] = {}
_jobs_cache_lock = threading.Lock()


def _job_db_write(job_id: str, status: str, result: Optional[dict] = None, error: Optional[str] = None):
    """Upsert job row in DB and update in-memory cache atomically."""
    now = time.time()
    result_json = json.dumps(result, ensure_ascii=False) if result is not None else None
    with _db_lock:
        conn = get_db()
        try:
            conn.execute(
                """INSERT INTO jobs (job_id, status, result_json, error, created_at, updated_at)
                   VALUES (?,?,?,?,?,?)
                   ON CONFLICT(job_id) DO UPDATE SET
                       status=excluded.status,
                       result_json=excluded.result_json,
                       error=excluded.error,
                       updated_at=excluded.updated_at""",
                (job_id, status, result_json, error, now, now),
            )
            conn.commit()
        finally:
            conn.close()
    with _jobs_cache_lock:
        _jobs_cache[job_id] = {"status": status, "result": result, "error": error, "ts": now}


def _job_db_read(job_id: str) -> Optional[Dict[str, Any]]:
    """Read job from cache; fall back to DB (covers post-restart polling)."""
    with _jobs_cache_lock:
        cached = _jobs_cache.get(job_id)
    if cached is not None:
        return cached
    # Cache miss — could be after server restart; check DB.
    with _db_lock:
        conn = get_db()
        try:
            row = conn.execute("SELECT status, result_json, error FROM jobs WHERE job_id=?", (job_id,)).fetchone()
        finally:
            conn.close()
    if row is None:
        return None
    result = None
    if row["result_json"]:
        try:
            result = json.loads(row["result_json"])
        except Exception:
            pass
    entry = {"status": row["status"], "result": result, "error": row["error"], "ts": time.time()}
    with _jobs_cache_lock:
        _jobs_cache[job_id] = entry
    return entry


def _prune_jobs():
    """Delete completed jobs older than JOB_TTL_SECONDS from DB and cache."""
    cutoff = time.time() - JOB_TTL_SECONDS
    with _db_lock:
        conn = get_db()
        try:
            conn.execute("DELETE FROM jobs WHERE updated_at < ?", (cutoff,))
            conn.commit()
        finally:
            conn.close()
    with _jobs_cache_lock:
        stale = [jid for jid, j in _jobs_cache.items() if j["ts"] < cutoff]
        for jid in stale:
            del _jobs_cache[jid]


def _run_optimize_job(job_id: str, body: OptimizeReq):
    """Background worker — runs the full optimize pipeline and stores result in DB."""
    started_at = time.time()
    _job_db_write(job_id, "running")
    _spend_context.user_id = body.user_id or ""
    try:
        obj = _call_gpt(
            body.resume_text, body.jd_text,
            seniority=body.seniority, industry=body.industry,
            region=body.region,
        )
        if not obj:
            raise RuntimeError("AI 返回空响应")

        resp = _parse_response(obj)
        rewritten = _ensure_substantive_rewrite(body.resume_text, body.jd_text, resp.optimized)
        if rewritten and rewritten.strip() and rewritten.strip() != (resp.optimized or "").strip():
            resp.optimized = rewritten
            resp.after_total = max(resp.after_total, min(100, resp.before_total + 8))
            resp.dims_after = [max(a, min(100, b + 5)) for a, b in zip(resp.dims_after, resp.dims_before)]

        lang = _detect_language(body.jd_text)
        resp.optimized = _force_quantified_bullets(resp.optimized, lang=lang)

        # Post-optimization keyword coverage check
        jd_kw = _extract_jd_keywords(body.jd_text, lang)
        coverage = _check_keyword_coverage(resp.optimized, jd_kw)
        if coverage["coverage_pct"] < 0.75 and (coverage["missing"] or coverage["tech_missing"]):
            patched = _patch_missing_keywords(
                resp.optimized,
                (coverage["missing"] + coverage["tech_missing"])[:8],
                body.jd_text,
                lang=lang,
            )
            if patched != resp.optimized:
                resp.optimized = patched
                resp.after_total = min(100, resp.after_total + 3)

        ats_screening = _assess_ats_screening(resp.optimized, jd_kw, lang)
        resp.ats_screening = ats_screening
        if ats_screening.internal_score + 6 < resp.after_total:
            resp.after_total = max(resp.before_total, ats_screening.internal_score + 2)
            resp.match_score = resp.after_total
        resp.match_score = resp.after_total

        resp.analysis = _build_suggestion_tiers(_ensure_quant_actions(resp.analysis))
        if resp.analysis and resp.analysis.overall:
            resp.analysis.overall.must_fix = _dedup(
                list(resp.analysis.overall.must_fix or []) + list(ats_screening.must_fix or []),
                8,
            )
            if ats_screening.hard_fail_reasons:
                resp.analysis.overall.issues = _dedup(
                    list(resp.analysis.overall.issues or []) + list(ats_screening.hard_fail_reasons),
                    8,
                )

        # Always regenerate cover letter via the language-aware pipeline.
        # The main _call_gpt result is discarded for cover letter — it can't
        # reliably produce Chinese output even when JD is in Chinese.
        jd_lang = _detect_language(body.jd_text)
        needs_cover_letter_retry = (
            _cover_letter_needs_retry(resp.cover_letter)
            or _detect_language(resp.cover_letter or "") != jd_lang
        )
        if needs_cover_letter_retry:
            try:
                cover_letter, quality = _generate_cover_letter_only(
                    resume=resp.optimized, jd=body.jd_text,
                    style=body.style or "professional", industry=body.industry,
                    seniority=body.seniority, region=body.region, tone=body.tone,
                )
                if len(cover_letter) >= MIN_COVER_LETTER_LENGTH:
                    resp.cover_letter = cover_letter
                    resp.cover_letter_quality = quality
            except Exception:
                pass
        if not resp.cover_letter_quality:
            resp.cover_letter_quality = _evaluate_cover_letter_quality(
                resp.cover_letter or "", resp.optimized, body.jd_text)

        required_info = _extract_required_info(resp.analysis)
        quality_overall = int((resp.cover_letter_quality or {}).get("overall", 0))
        if _cover_letter_needs_retry(resp.cover_letter) or quality_overall < MIN_COVER_LETTER_QUALITY_SCORE:
            resp.cover_letter_status = "need_more_info"
            resp.need_more_info = True
            resp.required_info = required_info
        else:
            resp.cover_letter_status = "ok"
            resp.required_info = required_info

        resp = _apply_output_quality_guards(
            resp,
            resume_text=body.resume_text,
            jd_text=body.jd_text,
            required_info=list(resp.required_info or required_info),
        )

        resp.score_delta = max(0, int(resp.after_total) - int(resp.before_total))
        resp.value_proof = _build_value_proof(resp, _detect_language(body.jd_text))

        if body.user_id and body.user_id.strip():
            user_id = body.user_id.strip()
            record_id, created_at = _save_record(user_id, body.resume_text, body.jd_text, resp)
            resp.record_id = record_id
            resp.created_at = created_at
            token, exp = _issue_session_token(user_id)
            resp.session_token = token
            resp.session_expires_at = exp

        resp = _apply_trial_preview_watermark(resp, (body.user_id or "").strip())

        _job_db_write(job_id, "done", result=resp.model_dump())
        _log_reliability_event(
            "optimize_async_success",
            "info",
            {
                "job_id": job_id,
                "duration_ms": int((time.time() - started_at) * 1000),
                "user_id": (body.user_id or "")[:64],
                "score_delta": int(resp.score_delta or 0),
                "need_more_info": bool(resp.need_more_info),
            },
        )

    except Exception as e:
        traceback.print_exc()
        _log_reliability_event(
            "optimize_async_exception",
            "error",
            {
                "job_id": job_id,
                "duration_ms": int((time.time() - started_at) * 1000),
                "user_id": (body.user_id or "")[:64],
                "error": str(e)[:300],
            },
        )
        _job_db_write(job_id, "error", error=str(e))


class JobStartResp(BaseModel):
    job_id: str


class JobStatusResp(BaseModel):
    status: str               # "pending" | "running" | "done" | "error"
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


@app.post("/v1/optimize/start", response_model=JobStartResp)
def optimize_start(body: OptimizeReq, background_tasks: BackgroundTasks, x_api_key: Optional[str] = Header(None)):
    """Start an async optimize job. Returns job_id immediately; client polls /status."""
    _require_api_key(x_api_key)
    if not (body.user_id or "").strip():
        resolved_uid = _resolve_user_id_from_api_key(x_api_key, body.user_id)
        if resolved_uid:
            body.user_id = resolved_uid
    uid = (body.user_id or "").strip()
    if not uid or _find_user_by_uid(uid) is None:
        raise HTTPException(status_code=401, detail="login required: please sign in to optimize")
    body.user_id = uid
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text and jd_text are required")
    _validate_input_sizes(body.resume_text, body.jd_text)
    rl_key = f"optimize:{body.user_id or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_OPTIMIZE_PER_HOUR)
    _enforce_free_daily_quota(body.user_id or "")
    _prune_jobs()
    job_id = str(uuid.uuid4())
    _job_db_write(job_id, "pending")
    background_tasks.add_task(_run_optimize_job, job_id, body)
    return JobStartResp(job_id=job_id)


@app.get("/v1/optimize/status/{job_id}", response_model=JobStatusResp)
def optimize_status(job_id: str, x_api_key: Optional[str] = Header(None)):
    """Poll the status of an async optimize job."""
    _require_api_key(x_api_key)
    job = _job_db_read(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="job_id not found or expired")
    return JobStatusResp(status=job["status"], result=job.get("result"), error=job.get("error"))


@app.post("/v1/auth/session", response_model=SessionResp)
def create_session_token(body: SessionCreateReq, x_api_key: Optional[str] = Header(None)):
    if not body.user_id.strip():
        raise HTTPException(status_code=400, detail="user_id is required")
    if not SERVER_API_KEY:
        raise HTTPException(status_code=503, detail="Session auth is not configured on server")
    if (x_api_key or "").strip() != SERVER_API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")
    token, exp = _issue_session_token(body.user_id.strip())
    return SessionResp(session_token=token, expires_at=exp)


# Runtime model guards for auth/admin routes. These are defined here to avoid
# import-order issues when legacy duplicated blocks exist in this monolithic file.
class SendOtpReq(BaseModel):
    channel: str = Field(..., min_length=4, max_length=8)
    target: str = Field(..., min_length=4, max_length=320)
    purpose: str = Field(..., min_length=3, max_length=32)


class SendOtpResp(BaseModel):
    ok: bool = True
    channel: str
    target_masked: str
    expires_in_sec: int
    dev_code: Optional[str] = None


class VerifyOtpReq(BaseModel):
    channel: str = Field(..., min_length=4, max_length=8)
    target: str = Field(..., min_length=4, max_length=320)
    purpose: str = Field(..., min_length=3, max_length=32)
    code: str = Field(..., min_length=4, max_length=12)


class ForgotPasswordReq(BaseModel):
    identifier: str = Field(..., min_length=3, max_length=320)


class ResetPasswordReq(BaseModel):
    identifier: str = Field(..., min_length=3, max_length=320)
    code: str = Field(..., min_length=4, max_length=12)
    new_password: str = Field(..., min_length=8, max_length=128)


class AdminUserRow(BaseModel):
    user_id: str
    email: Optional[str] = None
    phone: Optional[str] = None
    name: str = ""
    provider: str = "password"
    email_verified: bool = False
    phone_verified: bool = False
    status: str = "active"
    created_at: int
    updated_at: int
    last_login_at: Optional[int] = None


class AdminUserListResp(BaseModel):
    total: int
    items: List[AdminUserRow] = []


class AdminUserStatusReq(BaseModel):
    status: str = Field(..., min_length=6, max_length=12)


@app.post("/v1/auth/otp/send", response_model=SendOtpResp)
def send_auth_otp(body: SendOtpReq):
    channel = _normalize_otp_channel(body.channel)
    purpose = _normalize_otp_purpose(body.purpose)
    target = _normalize_otp_target(channel, body.target)
    code = _issue_otp(channel, target, purpose)
    delivered = _deliver_otp(channel, target, code, purpose)
    if not delivered and not AUTH_DEV_DELIVERY:
        raise HTTPException(status_code=503, detail=f"{channel} delivery provider not configured")
    return SendOtpResp(
        ok=True,
        channel=channel,
        target_masked=_mask_target(channel, target),
        expires_in_sec=AUTH_OTP_TTL_SEC,
        dev_code=code if (AUTH_DEV_DELIVERY and not delivered) else None,
    )


@app.post("/v1/auth/otp/verify")
def verify_auth_otp(body: VerifyOtpReq):
    channel = _normalize_otp_channel(body.channel)
    purpose = _normalize_otp_purpose(body.purpose)
    target = _normalize_otp_target(channel, body.target)
    ok = _verify_otp(channel, target, purpose, (body.code or "").strip(), consume=True)
    if not ok:
        raise HTTPException(status_code=401, detail="invalid or expired otp code")
    return {"ok": True, "channel": channel, "target_masked": _mask_target(channel, target)}


@app.post("/v1/auth/password/forgot")
def forgot_password(body: ForgotPasswordReq):
    identifier = (body.identifier or "").strip()
    email = _norm_email(identifier)
    phone = _norm_phone(identifier)
    user = _find_user_by_email(email) if "@" in identifier else _find_user_by_phone(phone)
    # Avoid account enumeration: always return ok.
    if not user:
        return {"ok": True}
    channel = "email" if ("@" in identifier and email) else "phone"
    target = email if channel == "email" else phone
    code = _issue_otp(channel, target, "reset_password")
    delivered = _deliver_otp(channel, target, code, "reset_password")
    if not delivered and not AUTH_DEV_DELIVERY:
        return {"ok": True}
    return {
        "ok": True,
        "channel": channel,
        "target_masked": _mask_target(channel, target),
        "dev_code": code if (AUTH_DEV_DELIVERY and not delivered) else None,
    }


@app.post("/v1/auth/password/reset")
def reset_password(body: ResetPasswordReq):
    identifier = (body.identifier or "").strip()
    new_password = (body.new_password or "").strip()
    if len(new_password) < AUTH_RESET_MIN_PASSWORD_LEN:
        raise HTTPException(status_code=400, detail=f"new_password must be at least {AUTH_RESET_MIN_PASSWORD_LEN} chars")
    email = _norm_email(identifier)
    phone = _norm_phone(identifier)
    channel = "email" if "@" in identifier else "phone"
    target = email if channel == "email" else phone
    if not target:
        raise HTTPException(status_code=400, detail="invalid identifier")
    if not _verify_otp(channel, target, "reset_password", (body.code or "").strip(), consume=True):
        raise HTTPException(status_code=401, detail="invalid or expired reset code")
    user = _find_user_by_email(email) if channel == "email" else _find_user_by_phone(phone)
    if not user:
        raise HTTPException(status_code=404, detail="user not found")
    with _db_lock:
        conn = get_db()
        try:
            conn.execute(
                "UPDATE users SET password_hash=?, updated_at=? WHERE user_id=?",
                (_password_hash(new_password), int(time.time()), user["user_id"]),
            )
            conn.commit()
        finally:
            conn.close()
    return {"ok": True}


@app.post("/v1/auth/email/send-verification")
def send_email_verification(authorization: Optional[str] = Header(None)):
    uid = _verify_session_token(_extract_bearer(authorization))
    row = _find_user_by_uid(uid)
    if not row:
        raise HTTPException(status_code=404, detail="user not found")
    email = _norm_email(row["email"])
    if not email:
        raise HTTPException(status_code=400, detail="account has no email")
    code = _issue_otp("email", email, "verify_email")
    delivered = _deliver_otp("email", email, code, "verify_email")
    if not delivered and not AUTH_DEV_DELIVERY:
        raise HTTPException(status_code=503, detail="email delivery provider not configured")
    return {
        "ok": True,
        "target_masked": _mask_target("email", email),
        "dev_code": code if (AUTH_DEV_DELIVERY and not delivered) else None,
    }


@app.post("/v1/auth/email/verify")
def verify_email_code(body: VerifyOtpReq, authorization: Optional[str] = Header(None)):
    uid = _verify_session_token(_extract_bearer(authorization))
    row = _find_user_by_uid(uid)
    if not row:
        raise HTTPException(status_code=404, detail="user not found")
    email = _norm_email(row["email"])
    if not email:
        raise HTTPException(status_code=400, detail="account has no email")
    if _normalize_otp_channel(body.channel) != "email":
        raise HTTPException(status_code=400, detail="channel must be email")
    if _normalize_otp_purpose(body.purpose) != "verify_email":
        raise HTTPException(status_code=400, detail="purpose must be verify_email")
    if not _verify_otp("email", email, "verify_email", (body.code or "").strip(), consume=True):
        raise HTTPException(status_code=401, detail="invalid or expired verification code")
    with _db_lock:
        conn = get_db()
        try:
            conn.execute(
                "UPDATE users SET email_verified=1, updated_at=? WHERE user_id=?",
                (int(time.time()), uid),
            )
            conn.commit()
        finally:
            conn.close()
    return {"ok": True}


@app.post("/v1/auth/register", response_model=AuthUserResp)
def register_user(body: RegisterReq):
    email = _norm_email(body.email)
    phone = _norm_phone(body.phone)
    password = (body.password or "").strip()
    name = (body.name or "").strip() or "User"

    if not email and not phone:
        raise HTTPException(status_code=400, detail="email or phone required")
    if len(password) < 6:
        raise HTTPException(status_code=400, detail="password must be at least 6 chars")

    if email and _find_user_by_email(email):
        raise HTTPException(status_code=409, detail="email already registered")
    if phone and _find_user_by_phone(phone):
        raise HTTPException(status_code=409, detail="phone already registered")

    email_verified = 0
    phone_verified = 0
    if email:
        email_code = (body.email_otp_code or "").strip()
        if not email_code:
            raise HTTPException(status_code=400, detail="email_otp_code required when registering with email")
        if not _verify_otp("email", email, "register", email_code, consume=True):
            raise HTTPException(status_code=401, detail="invalid email otp")
        email_verified = 1
    if phone:
        code = (body.phone_otp_code or "").strip()
        if not code:
            raise HTTPException(status_code=400, detail="phone_otp_code required when registering with phone")
        if not _verify_otp("phone", phone, "register", code, consume=True):
            raise HTTPException(status_code=401, detail="invalid phone otp")
        phone_verified = 1

    now = int(time.time())
    uid = f"usr-{uuid.uuid4().hex[:20]}"
    pwh = _password_hash(password)
    with _db_lock:
        conn = get_db()
        try:
            conn.execute(
                """INSERT INTO users (user_id, email, phone, password_hash, name, provider, google_sub, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, 'password', NULL, ?, ?)""",
                (uid, email or None, phone or None, pwh, name, now, now),
            )
            conn.execute(
                "UPDATE users SET email_verified=?, phone_verified=?, status='active', updated_at=? WHERE user_id=?",
                (email_verified, phone_verified, now, uid),
            )
            conn.commit()
        finally:
            conn.close()

    row = {
        "user_id": uid,
        "email": email or None,
        "phone": phone or None,
        "name": name,
        "provider": "password",
        "email_verified": email_verified,
        "phone_verified": phone_verified,
        "status": "active",
    }
    return _user_row_to_resp(row)


@app.post("/v1/auth/login", response_model=AuthUserResp)
def login_user(body: LoginReq):
    identifier = (body.identifier or "").strip()
    password = (body.password or "").strip()
    if not identifier or not password:
        raise HTTPException(status_code=400, detail="identifier and password required")

    email = _norm_email(identifier)
    phone = _norm_phone(identifier)
    row = _find_user_by_email(email) if "@" in identifier else _find_user_by_phone(phone)
    if not row:
        raise HTTPException(status_code=401, detail="invalid credentials")
    rowd = dict(row)
    if str(rowd.get("provider") or "") == "google":
        raise HTTPException(status_code=400, detail="use Google login for this account")
    if str(rowd.get("status") or "active") != "active":
        raise HTTPException(status_code=403, detail="account is blocked")
    if not _password_verify(password, str(rowd.get("password_hash") or "")):
        raise HTTPException(status_code=401, detail="invalid credentials")

    with _db_lock:
        conn = get_db()
        try:
            conn.execute(
                "UPDATE users SET updated_at=?, last_login_at=? WHERE user_id=?",
                (int(time.time()), int(time.time()), rowd["user_id"]),
            )
            conn.commit()
        finally:
            conn.close()
    return _user_row_to_resp(rowd)


@app.post("/v1/auth/google", response_model=AuthUserResp)
def auth_google(body: GoogleAuthReq):
    info = _verify_google_id_token(body.id_token)
    sub = str(info.get("sub") or "")
    email = _norm_email(info.get("email"))
    name = (info.get("name") or "").strip() or "Google User"

    row = _find_user_by_google_sub(sub)
    now = int(time.time())
    if row is None and email:
        by_email = _find_user_by_email(email)
        if by_email:
            if str(by_email["status"] or "active") != "active":
                raise HTTPException(status_code=403, detail="account is blocked")
            with _db_lock:
                conn = get_db()
                try:
                    conn.execute(
                        "UPDATE users SET google_sub=?, provider='google', email_verified=1, updated_at=?, last_login_at=? WHERE user_id=?",
                        (sub, now, now, by_email["user_id"]),
                    )
                    conn.commit()
                finally:
                    conn.close()
            row = _find_user_by_uid(str(by_email["user_id"]))

    if row is None:
        uid = f"usr-{uuid.uuid4().hex[:20]}"
        with _db_lock:
            conn = get_db()
            try:
                conn.execute(
                    """INSERT INTO users (user_id, email, phone, password_hash, name, provider, google_sub, created_at, updated_at)
                       VALUES (?, ?, NULL, NULL, ?, 'google', ?, ?, ?)""",
                    (uid, email or None, name, sub, now, now),
                )
                conn.execute(
                    "UPDATE users SET email_verified=1, status='active', last_login_at=?, updated_at=? WHERE user_id=?",
                    (now, now, uid),
                )
                conn.commit()
            finally:
                conn.close()
        row = _find_user_by_uid(uid)

    if row is not None and str(row["status"] or "active") != "active":
        raise HTTPException(status_code=403, detail="account is blocked")

    return _user_row_to_resp(dict(row))

@app.post("/v1/auth/guest")
def create_guest_session(body: GuestSessionReq, request: Request):
    """Bootstrap a session token for anonymous web / mobile users.
    No API key required — rate-limited per IP to prevent token farming.
    The returned session_token can be used as the X-API-Key value on all
    subsequent requests, so existing endpoint signatures don't need to change.
    """
    ip = (request.client.host if request.client else None) or "unknown"
    _rate_limit_check(f"guest:{ip}", 12)  # 12 new sessions per IP per hour
    uid = (body.device_id or "").strip()
    if not uid or len(uid) < 4:
        uid = f"guest-{uuid.uuid4().hex[:16]}"
    token, exp = _issue_session_token(uid)
    return {"session_token": token, "expires_at": exp, "user_id": uid}


@app.get("/v1/auth/me", response_model=AuthUserResp)
def auth_me(authorization: Optional[str] = Header(None)):
    uid = _verify_session_token(_extract_bearer(authorization))
    row = _find_user_by_uid(uid)
    if row is None:
        raise HTTPException(status_code=404, detail="user not found")
    if str(row["status"] or "active") != "active":
        raise HTTPException(status_code=403, detail="account is blocked")
    return _user_row_to_resp(dict(row))


@app.get("/v1/admin/users", response_model=AdminUserListResp)
def admin_list_users(
    q: str = Query("", max_length=128),
    status: str = Query("", max_length=12),
    provider: str = Query("", max_length=16),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    x_api_key: Optional[str] = Header(None),
):
    _require_api_key(x_api_key)
    where = []
    params: List[Any] = []
    if q.strip():
        like = f"%{q.strip().lower()}%"
        where.append("(LOWER(COALESCE(name,'')) LIKE ? OR LOWER(COALESCE(email,'')) LIKE ? OR LOWER(COALESCE(phone,'')) LIKE ? OR LOWER(user_id) LIKE ?)")
        params.extend([like, like, like, like])
    if status.strip():
        where.append("status=?")
        params.append(status.strip().lower())
    if provider.strip():
        where.append("provider=?")
        params.append(provider.strip().lower())
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    with _db_lock:
        conn = get_db()
        try:
            total_row = conn.execute(f"SELECT COUNT(*) AS c FROM users{where_sql}", tuple(params)).fetchone()
            rows = conn.execute(
                f"""SELECT user_id, email, phone, name, provider, email_verified, phone_verified, status,
                           created_at, updated_at, last_login_at
                    FROM users{where_sql}
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?""",
                tuple(params + [limit, offset]),
            ).fetchall()
        finally:
            conn.close()
    items = [
        AdminUserRow(
            user_id=str(r["user_id"]),
            email=r["email"],
            phone=r["phone"],
            name=str(r["name"] or ""),
            provider=str(r["provider"] or "password"),
            email_verified=bool(int(r["email_verified"] or 0)),
            phone_verified=bool(int(r["phone_verified"] or 0)),
            status=str(r["status"] or "active"),
            created_at=int(r["created_at"] or 0),
            updated_at=int(r["updated_at"] or 0),
            last_login_at=(int(r["last_login_at"]) if r["last_login_at"] is not None else None),
        )
        for r in rows
    ]
    total = int(total_row["c"]) if total_row else 0
    return AdminUserListResp(total=total, items=items)


@app.get("/v1/admin/users/{user_id}")
def admin_user_detail(user_id: str, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    row = _find_user_by_uid(user_id.strip())
    if row is None:
        raise HTTPException(status_code=404, detail="user not found")
    with _db_lock:
        conn = get_db()
        try:
            rec = conn.execute(
                "SELECT COUNT(*) AS cnt, MAX(created_at) AS last_at FROM records WHERE user_id=?",
                (user_id.strip(),),
            ).fetchone()
        finally:
            conn.close()
    data = dict(row)
    data["record_count"] = int(rec["cnt"] if rec else 0)
    data["last_record_at"] = int(rec["last_at"]) if rec and rec["last_at"] is not None else None
    data["email_verified"] = bool(int(data.get("email_verified") or 0))
    data["phone_verified"] = bool(int(data.get("phone_verified") or 0))
    return data


@app.patch("/v1/admin/users/{user_id}/status")
def admin_set_user_status(user_id: str, body: AdminUserStatusReq, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    new_status = (body.status or "").strip().lower()
    if new_status not in {"active", "blocked"}:
        raise HTTPException(status_code=400, detail="status must be active or blocked")
    with _db_lock:
        conn = get_db()
        try:
            row = conn.execute("SELECT user_id FROM users WHERE user_id=?", (user_id.strip(),)).fetchone()
            if not row:
                raise HTTPException(status_code=404, detail="user not found")
            conn.execute(
                "UPDATE users SET status=?, updated_at=? WHERE user_id=?",
                (new_status, int(time.time()), user_id.strip()),
            )
            conn.commit()
        finally:
            conn.close()
    return {"ok": True, "user_id": user_id.strip(), "status": new_status}

@app.post("/v1/generate-cover-letter")
def generate_cover_letter(body: OptimizeReq, x_api_key: Optional[str] = Header(None)):
    """Single cover-letter regeneration endpoint (used by the “regenerate” button)."""
    _require_api_key(x_api_key)
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text and jd_text are required")
    try:
        cover_letter, quality = _generate_cover_letter_only(
            resume=body.resume_text,
            jd=body.jd_text,
            style=body.style or "professional",
            industry=body.industry,
            seniority=body.seniority,
            region=body.region,
            tone=body.tone,
        )
        if not cover_letter:
            raise HTTPException(status_code=502, detail="AI failed to generate a cover letter. Please retry.")
        quality_overall = int((quality or {}).get("overall", 0))
        required_info = []
        need_more_info = _cover_letter_needs_retry(cover_letter) or quality_overall < MIN_COVER_LETTER_QUALITY_SCORE
        if need_more_info:
            required_info = _dedup(
                (quality.get("feedback") or []) + ["请补充更具体的项目成果指标、业务场景与职责边界。"],
                6
            )
            _record_quality_failure(
                reason="cover_letter_low_quality",
                quality=quality or {},
                required_info=required_info,
                resume_text=body.resume_text,
                jd_text=body.jd_text,
                cover_letter=cover_letter,
            )
        return {
            "cover_letter": cover_letter,
            "quality": quality,
            "status": "need_more_info" if need_more_info else "ok",
            "need_more_info": need_more_info,
            "required_info": required_info,
        }
    except HTTPException:
        raise
    except Exception as e:
        print("ERROR generating cover letter:", repr(e))
        traceback.print_exc()
        raise HTTPException(status_code=502, detail="Cover letter generation failed. Please retry.")

@app.get("/v1/records", response_model=List[RecordOut])
def list_records(
    limit: int = Query(50, ge=1, le=200),
    authorization: Optional[str] = Header(None),
):
    user_id = _verify_session_token(_extract_bearer(authorization))
    with _db_lock:
        conn = get_db()
        rows = conn.execute(
            "SELECT * FROM records WHERE user_id=? ORDER BY created_at DESC LIMIT ?",
            (user_id, limit)
        ).fetchall()
        conn.close()
    return [_row_to_record(r) for r in rows]

@app.delete("/v1/records/{record_id}")
def delete_record(record_id: int, authorization: Optional[str] = Header(None)):
    user_id = _verify_session_token(_extract_bearer(authorization))
    with _db_lock:
        conn = get_db()
        cur = conn.execute(
            "DELETE FROM records WHERE id=? AND user_id=?", (record_id, user_id)
        )
        conn.commit()
        deleted = cur.rowcount
        conn.close()
    if deleted == 0:
        raise HTTPException(status_code=404, detail="Record not found")
    return {"ok": True}

@app.post("/v1/records/clear")
def clear_records(authorization: Optional[str] = Header(None)):
    user_id = _verify_session_token(_extract_bearer(authorization))
    with _db_lock:
        conn = get_db()
        conn.execute("DELETE FROM records WHERE user_id=?", (user_id,))
        conn.commit()
        conn.close()
    return {"ok": True}
@app.get("/v1/quality/failures", response_model=List[QualityFailureOut])
def list_quality_failures(
    limit: int = Query(50, ge=1, le=200),
    x_api_key: Optional[str] = Header(None),
):
    _require_api_key(x_api_key)
    with _db_lock:
        conn = get_db()
        rows = conn.execute(
            """SELECT id, reason, quality_json, required_info, resume_excerpt, jd_excerpt, cover_letter, created_at
               FROM quality_failures ORDER BY created_at DESC LIMIT ?""",
            (limit,),
        ).fetchall()
        conn.close()
    out: List[QualityFailureOut] = []
    for r in rows:
        out.append(QualityFailureOut(
            id=r["id"],
            reason=r["reason"],
            quality=_try_parse_json(r["quality_json"] or "{}") or {},
            required_info=_try_parse_json(r["required_info"] or "[]") or [],
            resume_excerpt=r["resume_excerpt"],
            jd_excerpt=r["jd_excerpt"],
            cover_letter=r["cover_letter"],
            created_at=r["created_at"],
        ))
    return out


# ── Admin: real-time API cost monitoring ──────────────────────────────────────
@app.get("/v1/admin/spending")
def admin_spending(
    days: int = Query(30, ge=1, le=365),
    x_api_key: Optional[str] = Header(None),
):
    """Return aggregated OpenAI API spend data. Requires SERVER_API_KEY.

    Query params:
      days=N  — look back N days (default 30, max 365)

    Response includes:
      - total_cost_usd: total spend in the window
      - total_calls: number of API calls
      - total_tokens: total tokens consumed
      - by_model: breakdown per model
      - by_day: daily totals (last 30 days max in this view)
      - by_endpoint: breakdown per call type
      - top_users: top 10 users by cost (user_id only, no PII)
    """
    _require_api_key(x_api_key)
    since = int(time.time()) - days * 86400
    with _db_lock:
        conn = get_db()
        try:
            # Overall totals
            totals = conn.execute(
                "SELECT COUNT(*) AS calls, SUM(total_tokens) AS tokens, SUM(cost_usd) AS cost "
                "FROM api_spend WHERE created_at >= ?",
                (since,),
            ).fetchone()

            # By model
            model_rows = conn.execute(
                "SELECT model, COUNT(*) AS calls, SUM(prompt_tokens) AS pt, "
                "SUM(completion_tokens) AS ct, SUM(total_tokens) AS tt, SUM(cost_usd) AS cost "
                "FROM api_spend WHERE created_at >= ? GROUP BY model ORDER BY cost DESC",
                (since,),
            ).fetchall()

            # By day (UTC date)
            day_rows = conn.execute(
                "SELECT CAST(created_at/86400 AS INTEGER)*86400 AS day_ts, "
                "COUNT(*) AS calls, SUM(total_tokens) AS tokens, SUM(cost_usd) AS cost "
                "FROM api_spend WHERE created_at >= ? "
                "GROUP BY day_ts ORDER BY day_ts ASC",
                (since,),
            ).fetchall()

            # By endpoint
            ep_rows = conn.execute(
                "SELECT endpoint, COUNT(*) AS calls, SUM(cost_usd) AS cost "
                "FROM api_spend WHERE created_at >= ? AND endpoint != '' "
                "GROUP BY endpoint ORDER BY cost DESC",
                (since,),
            ).fetchall()

            # Top users by cost
            user_rows = conn.execute(
                "SELECT user_id, COUNT(*) AS calls, SUM(cost_usd) AS cost "
                "FROM api_spend WHERE created_at >= ? AND user_id != '' "
                "GROUP BY user_id ORDER BY cost DESC LIMIT 10",
                (since,),
            ).fetchall()
        finally:
            conn.close()

    return {
        "window_days": days,
        "since_ts": since,
        "total_cost_usd": round(float(totals["cost"] or 0), 6),
        "total_calls": int(totals["calls"] or 0),
        "total_tokens": int(totals["tokens"] or 0),
        "by_model": [
            {
                "model": r["model"],
                "calls": int(r["calls"]),
                "prompt_tokens": int(r["pt"] or 0),
                "completion_tokens": int(r["ct"] or 0),
                "total_tokens": int(r["tt"] or 0),
                "cost_usd": round(float(r["cost"] or 0), 6),
            }
            for r in model_rows
        ],
        "by_day": [
            {
                "date": time.strftime("%Y-%m-%d", time.gmtime(int(r["day_ts"]))),
                "calls": int(r["calls"]),
                "tokens": int(r["tokens"] or 0),
                "cost_usd": round(float(r["cost"] or 0), 6),
            }
            for r in day_rows
        ],
        "by_endpoint": [
            {
                "endpoint": r["endpoint"],
                "calls": int(r["calls"]),
                "cost_usd": round(float(r["cost"] or 0), 6),
            }
            for r in ep_rows
        ],
        "top_users_by_cost": [
            {
                "user_id": r["user_id"],
                "calls": int(r["calls"]),
                "cost_usd": round(float(r["cost"] or 0), 6),
            }
            for r in user_rows
        ],
    }



    with _db_lock:
        conn = get_db()
        row = conn.execute("SELECT id, created_at FROM regression_cases WHERE name=?", (body.name,)).fetchone()
        if row:
            conn.execute(
                """UPDATE regression_cases
                   SET resume_text=?, jd_text=?, style=?, industry=?, seniority=?, region=?, tone=?, min_score=?, updated_at=?
                   WHERE id=?""",
                (
                    body.resume_text,
                    body.jd_text,
                    body.style,
                    body.industry,
                    body.seniority,
                    body.region,
                    body.tone,
                    body.min_score,
                    now,
                    row["id"],
                ),
            )
            case_id = row["id"]
            created_at = row["created_at"]
        else:
            case_id = conn.insert_get_id(
                """INSERT INTO regression_cases
                   (name, resume_text, jd_text, style, industry, seniority, region, tone, min_score, created_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    body.name,
                    body.resume_text,
                    body.jd_text,
                    body.style,
                    body.industry,
                    body.seniority,
                    body.region,
                    body.tone,
                    body.min_score,
                    now,
                    now,
                ),
            )
            created_at = now
        conn.commit()
        conn.close()
    return RegressionCaseOut(
        id=case_id,
        created_at=created_at,
        updated_at=now,
        **body.model_dump(),
    )

@app.get("/v1/quality/regression-cases", response_model=List[RegressionCaseOut])
def list_regression_cases(
    limit: int = Query(100, ge=1, le=500),
    x_api_key: Optional[str] = Header(None),
):
    _require_api_key(x_api_key)
    with _db_lock:
        conn = get_db()
        rows = conn.execute(
            """SELECT id, name, resume_text, jd_text, style, industry, seniority, region, tone, min_score, created_at, updated_at
               FROM regression_cases ORDER BY updated_at DESC LIMIT ?""",
            (limit,),
        ).fetchall()
        conn.close()
    return [
        RegressionCaseOut(
            id=r["id"],
            name=r["name"],
            resume_text=r["resume_text"],
            jd_text=r["jd_text"],
            style=r["style"],
            industry=r["industry"],
            seniority=r["seniority"],
            region=r["region"],
            tone=r["tone"],
            min_score=r["min_score"],
            created_at=r["created_at"],
            updated_at=r["updated_at"],
        )
        for r in rows
    ]

@app.post("/v1/quality/regression-run", response_model=RegressionRunResp)
def run_regression_cases(
    limit: int = Query(30, ge=1, le=100),
    x_api_key: Optional[str] = Header(None),
):
    _require_api_key(x_api_key)
    with _db_lock:
        conn = get_db()
        rows = conn.execute(
            """SELECT id, name, resume_text, jd_text, style, industry, seniority, region, tone, min_score
               FROM regression_cases ORDER BY updated_at DESC LIMIT ?""",
            (limit,),
        ).fetchall()
        conn.close()

    items: List[RegressionRunItem] = []
    for r in rows:
        letter, quality = _generate_cover_letter_only(
            resume=r["resume_text"],
            jd=r["jd_text"],
            style=r["style"] or "professional",
            industry=r["industry"],
            seniority=r["seniority"],
            region=r["region"],
            tone=r["tone"],
        )
        score = int((quality or {}).get("overall", 0))
        threshold = int(r["min_score"] or MIN_COVER_LETTER_QUALITY_SCORE)
        passed = bool(letter) and score >= threshold and not _cover_letter_needs_retry(letter)
        items.append(RegressionRunItem(
            case_id=r["id"],
            name=r["name"],
            score=score,
            pass_threshold=threshold,
            passed=passed,
            feedback=_dedup((quality or {}).get("feedback"), 4),
        ))

    passed = len([x for x in items if x.passed])
    total = len(items)
    failed = total - passed
    pass_rate = round((passed / total), 4) if total else 0.0
    return RegressionRunResp(
        total=total,
        passed=passed,
        failed=failed,
        pass_rate=pass_rate,
        items=items,
    )

# ===== Billing / Entitlement =====

class VerifyPurchaseReq(BaseModel):
    user_id: str
    product_id: str
    purchase_token: str
    order_id: Optional[str] = None

class EntitlementResp(BaseModel):
    plan: str = ""
    expires_at: int = 0
    is_active: bool = False
    auto_renewing: Optional[bool] = None
    free_limit: int = 0
    free_used: int = 0
    free_remaining: int = 0
    pro_monthly_limit: int = 0
    pro_used_30d: int = 0
    pro_remaining_30d: int = 0


def _get_usage_snapshot(user_id: str) -> Dict[str, int]:
    if not user_id:
        return {
            "free_limit": max(0, FREE_OPTIMIZE_LIFETIME),
            "free_used": 0,
            "free_remaining": max(0, FREE_OPTIMIZE_LIFETIME),
            "pro_monthly_limit": max(0, PRO_OPTIMIZE_PER_MONTH),
            "pro_used_30d": 0,
            "pro_remaining_30d": max(0, PRO_OPTIMIZE_PER_MONTH),
        }
    now = int(time.time())
    since_30d = now - 30 * 86400
    with _db_lock:
        conn = get_db()
        try:
            row_all = conn.execute(
                "SELECT COUNT(*) AS c FROM records WHERE user_id=?",
                (user_id,),
            ).fetchone()
            row_30d = conn.execute(
                "SELECT COUNT(*) AS c FROM records WHERE user_id=? AND created_at>=?",
                (user_id, since_30d),
            ).fetchone()
        finally:
            conn.close()
    free_used = int((row_all or {}).get("c", 0)) if row_all is not None else 0
    pro_used_30d = int((row_30d or {}).get("c", 0)) if row_30d is not None else 0
    free_limit = max(0, FREE_OPTIMIZE_LIFETIME)
    pro_limit = max(0, PRO_OPTIMIZE_PER_MONTH)
    return {
        "free_limit": free_limit,
        "free_used": free_used,
        "free_remaining": max(0, free_limit - free_used),
        "pro_monthly_limit": pro_limit,
        "pro_used_30d": pro_used_30d,
        "pro_remaining_30d": max(0, pro_limit - pro_used_30d) if pro_limit > 0 else 0,
    }

def _entitlement_from_row(row) -> EntitlementResp:
    user_id = ""
    if row is not None:
        try:
            user_id = str(row["user_id"] or "")
        except Exception:
            user_id = ""
    usage = _get_usage_snapshot(user_id)
    if row is None:
        return EntitlementResp(**usage)
    now = int(time.time())
    active = bool(row["is_active"]) and int(row["expires_at"]) > now
    return EntitlementResp(
        plan=row["plan"] or "",
        expires_at=int(row["expires_at"] or 0),
        is_active=active,
        auto_renewing=bool(row["auto_renewing"]),
        **usage,
    )

def _get_active_entitlement(user_id: str) -> Optional[sqlite3.Row]:
    if not user_id:
        return None
    with _db_lock:
        conn = get_db()
        try:
            row = conn.execute(
                """SELECT * FROM entitlements
                   WHERE user_id = ? AND is_active = 1 AND expires_at > ?
                   ORDER BY expires_at DESC LIMIT 1""",
                (user_id, int(time.time())),
            ).fetchone()
            return row
        finally:
            conn.close()


# ----- Google Play Developer API verification -----
# Lazy-init the service so the server still boots when the SA isn't configured
# (the verify endpoint will reject in that case unless ALLOW_INSECURE_BILLING=1).
_play_service = None
_play_service_lock = threading.Lock()

def _get_play_service():
    global _play_service
    if _play_service is not None:
        return _play_service
    with _play_service_lock:
        if _play_service is not None:
            return _play_service
        try:
            from google.oauth2 import service_account
            from googleapiclient.discovery import build
            import google.auth
        except ImportError:
            return None
        scopes = ["https://www.googleapis.com/auth/androidpublisher"]
        creds = None
        if GOOGLE_PLAY_SA_JSON_RAW:
            try:
                info = json.loads(GOOGLE_PLAY_SA_JSON_RAW)
                creds = service_account.Credentials.from_service_account_info(info, scopes=scopes)
            except Exception as e:
                print(f"Play SA JSON parse failed: {e}")
        elif GOOGLE_PLAY_SA_FILE and os.path.exists(GOOGLE_PLAY_SA_FILE):
            try:
                creds = service_account.Credentials.from_service_account_file(GOOGLE_PLAY_SA_FILE, scopes=scopes)
            except Exception as e:
                print(f"Play SA file load failed: {e}")
        # Fallback to Application Default Credentials (ADC), useful when
        # org policy forbids service-account key creation and Workload
        # Identity or gcloud ADC is used instead.
        if creds is None:
            try:
                creds, _ = google.auth.default(scopes=scopes)
            except Exception as e:
                print(f"Play ADC load failed: {e}")
        if creds is None:
            return None
        try:
            _play_service = build("androidpublisher", "v3", credentials=creds, cache_discovery=False)
        except Exception as e:
            print(f"Play service build failed: {e}")
            return None
        return _play_service


def _verify_play_subscription(product_id: str, purchase_token: str) -> Dict[str, Any]:
    """Query Google Play for the real subscription state.

    Returns a dict with keys: ok (bool), expiry_ms (int), auto_renewing (bool),
    order_id (str|None), reason (str). When ok=False the purchase MUST be
    rejected — never anchor to the server clock for an unverified token.
    """
    svc = _get_play_service()
    if svc is None:
        if ALLOW_INSECURE_BILLING:
            # Explicit dev opt-in: anchor expiry to server clock from plan registry.
            plan_meta = SUBSCRIPTION_PLANS.get(product_id) or {"duration_sec": 31 * 86400}
            expiry_ms = (int(time.time()) + int(plan_meta["duration_sec"])) * 1000
            return {"ok": True, "expiry_ms": expiry_ms, "auto_renewing": True,
                    "order_id": None, "reason": "insecure_dev_mode"}
        return {"ok": False, "expiry_ms": 0, "auto_renewing": False,
                "order_id": None, "reason": "play_service_account_not_configured"}
    try:
        resp = svc.purchases().subscriptions().get(
            packageName=GOOGLE_PLAY_PACKAGE_NAME,
            subscriptionId=product_id,
            token=purchase_token,
        ).execute()
    except Exception as e:
        return {"ok": False, "expiry_ms": 0, "auto_renewing": False,
                "order_id": None, "reason": f"play_api_error: {e}"}
    # paymentState: 0 pending, 1 received, 2 free trial, 3 pending deferred upgrade
    payment_state = resp.get("paymentState")
    expiry_ms = int(resp.get("expiryTimeMillis") or 0)
    if payment_state not in (1, 2):
        return {"ok": False, "expiry_ms": expiry_ms, "auto_renewing": False,
                "order_id": resp.get("orderId"), "reason": f"invalid_payment_state: {payment_state}"}
    if expiry_ms <= int(time.time() * 1000):
        return {"ok": False, "expiry_ms": expiry_ms, "auto_renewing": False,
                "order_id": resp.get("orderId"), "reason": "subscription_expired"}
    return {
        "ok": True,
        "expiry_ms": expiry_ms,
        "auto_renewing": bool(resp.get("autoRenewing", True)),
        "order_id": resp.get("orderId"),
        "reason": "verified",
    }


def _verify_play_inapp(product_id: str, purchase_token: str) -> Dict[str, Any]:
    """Verify a one-time INAPP purchase via purchases().products().get().

    Returns same shape as _verify_play_subscription for uniform handling.
    """
    svc = _get_play_service()
    if svc is None:
        if ALLOW_INSECURE_BILLING:
            meta = INAPP_PRODUCTS.get(product_id) or {"duration_sec": 30 * 86400}
            expiry_ms = (int(time.time()) + int(meta["duration_sec"])) * 1000
            return {"ok": True, "expiry_ms": expiry_ms, "auto_renewing": False,
                    "order_id": None, "reason": "insecure_dev_mode"}
        return {"ok": False, "expiry_ms": 0, "auto_renewing": False,
                "order_id": None, "reason": "play_service_account_not_configured"}
    try:
        resp = svc.purchases().products().get(
            packageName=GOOGLE_PLAY_PACKAGE_NAME,
            productId=product_id,
            token=purchase_token,
        ).execute()
    except Exception as e:
        return {"ok": False, "expiry_ms": 0, "auto_renewing": False,
                "order_id": None, "reason": f"play_api_error: {e}"}
    # purchaseState: 0 = Purchased, 1 = Canceled, 2 = Pending
    purchase_state = resp.get("purchaseState")
    if purchase_state == 1:
        return {"ok": False, "expiry_ms": 0, "auto_renewing": False,
                "order_id": resp.get("orderId"), "reason": "purchase_canceled"}
    if purchase_state == 2:
        return {"ok": False, "expiry_ms": 0, "auto_renewing": False,
                "order_id": resp.get("orderId"), "reason": "purchase_pending"}
    # Acknowledge if not yet done (required within 3 days of purchase)
    if resp.get("acknowledgementState") == 0:
        try:
            svc.purchases().products().acknowledge(
                packageName=GOOGLE_PLAY_PACKAGE_NAME,
                productId=product_id,
                token=purchase_token,
                body={},
            ).execute()
        except Exception:
            pass  # Non-fatal: the purchase is still valid even if acknowledge fails transiently
    meta = INAPP_PRODUCTS.get(product_id) or {"duration_sec": 30 * 86400}
    expiry_ms = (int(time.time()) + int(meta["duration_sec"])) * 1000
    return {
        "ok": True,
        "expiry_ms": expiry_ms,
        "auto_renewing": False,
        "order_id": resp.get("orderId"),
        "reason": "verified",
    }
# Per-user sliding-window. Good enough for a single-dyno deployment; swap for
# Redis (e.g. `slowapi` + Redis backend) when scaling horizontally.
_rate_buckets: Dict[str, List[int]] = {}
_rate_lock = threading.Lock()

def _rate_limit_check(key: str, max_per_hour: int) -> None:
    if max_per_hour <= 0:
        return
    now = int(time.time())
    window_start = now - 3600
    with _rate_lock:
        history = [t for t in _rate_buckets.get(key, []) if t >= window_start]
        if len(history) >= max_per_hour:
            retry_after = max(1, history[0] + 3600 - now)
            raise HTTPException(
                status_code=429,
                detail=f"Rate limit: max {max_per_hour}/hour. Retry in {retry_after}s.",
            )
        history.append(now)
        _rate_buckets[key] = history


def _enforce_free_daily_quota(user_id: str) -> None:
    """Free-tier lifetime cap backed by the DB records table (survives restarts).

    Counts ALL completed optimize records for the user ever — not just today.
    Once FREE_OPTIMIZE_LIFETIME is reached the user must subscribe.
    This is restart-safe because the count is read from the DB.

    Pro subscribers bypass the cap entirely but have a monthly budget.
    """
    if FREE_OPTIMIZE_LIFETIME <= 0:
        return
    if not user_id or user_id.startswith("guest-") or not _is_registered_user(user_id):
        raise HTTPException(
            status_code=401,
            detail="Login required to claim free optimization quota.",
        )
    if not _is_active_user(user_id):
        raise HTTPException(status_code=403, detail="account is blocked")
    now = int(time.time())
    if _get_active_entitlement(user_id) is not None:
        if PRO_OPTIMIZE_PER_MONTH > 0:
            month_start = now - 30 * 86400
            pro_bucket = f"monthly_optimize_pro:{user_id}"
            with _rate_lock:
                history = [t for t in _rate_buckets.get(pro_bucket, []) if t >= month_start]
                if len(history) >= PRO_OPTIMIZE_PER_MONTH:
                    raise HTTPException(
                        status_code=429,
                        detail=(
                            f"Pro plan: monthly limit of {PRO_OPTIMIZE_PER_MONTH} optimizations reached. "
                            "Resets in 30 days. Contact support@cvdoor.app for a higher tier."
                        ),
                    )
                history.append(now)
                _rate_buckets[pro_bucket] = history
        return  # Pro users pass free-tier check

    # ── DB-backed lifetime count (restart-safe) ─────────────────────────────
    with _db_lock:
        conn = get_db()
        try:
            row = conn.execute(
                "SELECT COUNT(*) AS cnt FROM records WHERE user_id=?",
                (user_id,),
            ).fetchone()
            count_lifetime = int(row["cnt"]) if row else 0
        finally:
            conn.close()
    if count_lifetime >= FREE_OPTIMIZE_LIFETIME:
        raise HTTPException(
            status_code=402,
            detail=(
                f"Free trial: {FREE_OPTIMIZE_LIFETIME} lifetime optimizations used. "
                "Upgrade to Pro for unlimited use."
            ),
        )


def _is_pro_user(user_id: str) -> bool:
    if not user_id:
        return False
    return _get_active_entitlement(user_id.strip()) is not None


def _watermark_preview_text(text: Optional[str]) -> Optional[str]:
    if not text:
        return text
    step = max(2, TRIAL_WATERMARK_EVERY_N_LINES)
    lines = text.splitlines()
    if not lines:
        return text
    # Put the lock notice at the top first so users immediately understand
    # this is a trial preview, then repeat every N non-empty lines.
    out: List[str] = [TRIAL_WATERMARK_LINE, ""]
    non_empty = 0
    for idx, line in enumerate(lines):
        out.append(line)
        if line.strip():
            non_empty += 1
            if non_empty % step == 0 and idx < len(lines) - 1:
                out.append(TRIAL_WATERMARK_LINE)
    return "\n".join(out)


def _build_value_proof(resp: OptimizeResp, lang: str = "en") -> List[str]:
    delta = int(resp.after_total or 0) - int(resp.before_total or 0)
    kw = len(resp.added_keywords or [])
    if lang == "zh":
        if delta > 0:
            head = f"ATS总分从 {resp.before_total} 提升到 {resp.after_total}（+{delta}）"
        elif delta == 0:
            head = f"ATS总分维持在 {resp.after_total}（±0）"
        else:
            head = f"ATS总分从 {resp.before_total} 变为 {resp.after_total}（{delta}）"
        lines = [
            head,
            f"已植入 {kw} 个JD关键字",
            "工作经历要点已强化为‘动作 + 方法 + 量化结果’结构",
        ]
    else:
        if delta > 0:
            head = f"ATS score improved from {resp.before_total} to {resp.after_total} (+{delta})"
        elif delta == 0:
            head = f"ATS score held at {resp.after_total} (+/-0)"
        else:
            head = f"ATS score changed from {resp.before_total} to {resp.after_total} ({delta})"
        lines = [
            head,
            f"Embedded {kw} exact JD keywords",
            "Experience bullets were rebuilt into Action + Method + Quantified Impact",
        ]
    return lines


def _apply_trial_preview_watermark(resp: OptimizeResp, user_id: str) -> OptimizeResp:
    # Free users can see quality, but generated text is preview-watermarked.
    if _is_pro_user(user_id):
        resp.preview_locked = False
        resp.upgrade_cta = ""
        return resp
    lang = _detect_language(resp.optimized or "")
    resp.optimized = _watermark_preview_text(resp.optimized) or ""
    if resp.cover_letter:
        resp.cover_letter = _watermark_preview_text(resp.cover_letter)
    resp.preview_locked = True
    if lang == "zh":
        resp.upgrade_cta = "升级 Pro 解锁可直接投递的无水印完整版与导出功能。"
    else:
        resp.upgrade_cta = "Upgrade to Pro to unlock export-ready full text without watermark."
    return resp

@app.post("/v1/billing/verify", response_model=EntitlementResp)
def billing_verify(body: VerifyPurchaseReq, x_api_key: Optional[str] = Header(None)):
    """
    Verify a Google Play purchase token against the Play Developer API and
    persist the resulting entitlement. The DB row is the single source of
    truth for /v1/entitlements/{user_id} — the Android client cannot bypass
    it by tampering with shared prefs.

    Production setup:
      1. Create a service account in Google Cloud, grant "View financial data"
         in Play Console → Users & permissions.
      2. Set GOOGLE_PLAY_PACKAGE_NAME (matches Android applicationId).
      3. Provide credentials via GOOGLE_PLAY_SERVICE_ACCOUNT_JSON (env var,
         best for PaaS) or GOOGLE_APPLICATION_CREDENTIALS (file path).

    Dev escape hatch: set ALLOW_INSECURE_BILLING=1 to anchor expiry to the
    server clock without contacting Play. DO NOT enable in production.
    """
    _require_api_key(x_api_key)
    is_inapp = body.product_id in INAPP_PRODUCTS
    is_sub   = body.product_id in SUBSCRIPTION_PLANS
    if not is_inapp and not is_sub:
        raise HTTPException(status_code=400, detail=f"unknown product_id: {body.product_id}")
    if not body.purchase_token:
        raise HTTPException(status_code=400, detail="purchase_token is required")
    if not body.user_id.strip():
        raise HTTPException(status_code=400, detail="user_id is required")

    if is_inapp:
        result = _verify_play_inapp(body.product_id, body.purchase_token)
        plan_meta = INAPP_PRODUCTS[body.product_id]
    else:
        result = _verify_play_subscription(body.product_id, body.purchase_token)
        plan_meta = SUBSCRIPTION_PLANS[body.product_id]
    if not result["ok"]:
        # Mark any existing row inactive so a refunded / canceled sub is revoked.
        with _db_lock:
            conn = get_db()
            try:
                conn.execute(
                    "UPDATE entitlements SET is_active = 0, updated_at = ? "
                    "WHERE user_id = ? AND product_id = ?",
                    (int(time.time()), body.user_id, body.product_id),
                )
                conn.commit()
            finally:
                conn.close()
        raise HTTPException(status_code=402, detail=f"purchase verification failed: {result['reason']}")

    plan = plan_meta["plan"]
    expires_at = int(result["expiry_ms"] // 1000)
    auto_renewing = 1 if result["auto_renewing"] else 0
    order_id = result.get("order_id") or body.order_id
    now = int(time.time())

    with _db_lock:
        conn = get_db()
        try:
            conn.execute(
                """INSERT INTO entitlements
                       (user_id, product_id, purchase_token, order_id, plan,
                        expires_at, auto_renewing, is_active, created_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,1,?,?)
                   ON CONFLICT(user_id, product_id) DO UPDATE SET
                       purchase_token = excluded.purchase_token,
                       order_id       = excluded.order_id,
                       plan           = excluded.plan,
                       expires_at     = excluded.expires_at,
                       auto_renewing  = excluded.auto_renewing,
                       is_active      = 1,
                       updated_at     = excluded.updated_at""",
                (
                    body.user_id, body.product_id, body.purchase_token,
                    order_id, plan, expires_at, auto_renewing, now, now,
                ),
            )
            conn.commit()
        finally:
            conn.close()

    return EntitlementResp(
        plan=plan,
        expires_at=expires_at,
        is_active=True,
        auto_renewing=bool(auto_renewing),
    )

@app.get("/v1/entitlements/{user_id}", response_model=EntitlementResp)
def get_entitlement(user_id: str, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    row = _get_active_entitlement(user_id)
    return _entitlement_from_row(row)

# ===== Cover letter variants =====

class CoverLetterVariantOut(BaseModel):
    variant: str
    cover_letter: str
    quality: Dict[str, Any] = {}

class CoverLetterVariantsResp(BaseModel):
    jd_entities: Dict[str, Any] = {}
    resume_entities: Dict[str, Any] = {}
    variants: List[CoverLetterVariantOut] = []

@app.post("/v1/cover-letter/variants", response_model=CoverLetterVariantsResp)
def cover_letter_variants(body: OptimizeReq, x_api_key: Optional[str] = Header(None)):
    """Generate two distinct cover-letter variants (concise + detailed) in one
    call, sharing the heavy entity-extraction step. Lets the user pick the
    angle that fits the role best instead of being stuck with one draft.
    """
    _require_api_key(x_api_key)
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text and jd_text are required")
    jd_entities = _parse_jd_entities(body.jd_text)
    resume_entities = _parse_resume_entities(body.resume_text)
    out: List[CoverLetterVariantOut] = []
    for variant in ("concise", "detailed"):
        letter, quality = _generate_cover_letter_only(
            resume=body.resume_text,
            jd=body.jd_text,
            style=body.style or "professional",
            industry=body.industry,
            seniority=body.seniority,
            region=body.region,
            tone=body.tone,
            variant=variant,
            jd_entities=jd_entities,
            resume_entities=resume_entities,
        )
        out.append(CoverLetterVariantOut(
            variant=variant,
            cover_letter=letter,
            quality=quality,
        ))
    return CoverLetterVariantsResp(
        jd_entities=jd_entities,
        resume_entities=resume_entities,
        variants=out,
    )

# ===== Freemium preview =====
#
# A lightweight, no-auth scoring pass that lets first-time users see a REAL
# match score + the top keywords they hit / missed before being asked to pay.
# Conversion driver: replaces the "blind paywall" with "see truth -> upgrade".
# Cheap on tokens (no full optimization), so safe to leave open.

class PreviewReq(BaseModel):
    resume_text: str
    jd_text: str
    user_id: Optional[str] = None

class PreviewResp(BaseModel):
    match_score: int = 0
    summary: str = ""
    matched_keywords: List[str] = []
    missing_keywords: List[str] = []
    quick_wins: List[str] = []   # 2-3 highest-ROI fixes user can see for free

@app.post("/v1/preview", response_model=PreviewResp)
def preview(body: PreviewReq, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text and jd_text are required")
    # Preview is unauthenticated by design (freemium hook); rate-limit by user_id
    # if provided, otherwise by a global anonymous bucket so a single attacker
    # can't drain the OpenAI budget.
    rl_key = f"preview:{(getattr(body, 'user_id', '') or 'anonymous').strip() or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_PREVIEW_PER_HOUR)
    prompt = f"""You are an ATS quick-scoring engine. Output ONLY JSON:
{{
  "match_score": <0-100 integer reflecting JD ↔ resume fit>,
  "summary": "<one-sentence diagnosis in the JD's language>",
  "matched_keywords": ["up to 5 important keywords the resume already hits"],
  "missing_keywords": ["up to 5 important keywords from the JD that are missing"],
  "quick_wins": ["2-3 short fixes the candidate could apply, in the JD's language"]
}}

Resume:
\"\"\"{_resume_focus_excerpt(body.resume_text, 2400)}\"\"\"

JD:
\"\"\"{body.jd_text[:2400]}\"\"\""""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
        )
        obj = _try_parse_json((comp.choices[0].message.content or "").strip()) or {}
    except Exception as e:
        if DEBUG:
            print(f"preview error: {e}")
        obj = {}
    return PreviewResp(
        match_score=_clamp(obj.get("match_score", 0)),
        summary=str(obj.get("summary", "")).strip(),
        matched_keywords=_dedup(obj.get("matched_keywords"), 5),
        missing_keywords=_dedup(obj.get("missing_keywords"), 5),
        quick_wins=_dedup(obj.get("quick_wins"), 3),
    )

# ── Section-level regeneration ────────────────────────────────────────────────
# Lets users re-optimize a single bullet / paragraph without paying for a full
# re-run. This is THE feature gap vs. Teal, Rezi, Kickresume.
@app.post("/v1/optimize/section", response_model=SectionOptimizeResp)
def optimize_section(body: SectionOptimizeReq, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if not body.section_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="section_text and jd_text are required")
    rl_key = f"section:{body.user_id or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_OPTIMIZE_PER_HOUR)

    lang = _detect_language(body.jd_text)
    instr = (body.instructions or "").strip()
    ctx_block = _build_context_block(body.industry, body.seniority, None, None, lang)

    if lang == "en":
        sys_msg = (
            "You are a senior ATS resume editor. Rewrite the provided section so it: "
            "(1) directly targets the JD's must-have keywords and outcomes, "
            "(2) ends with a quantified impact sentence (Action + Metric + Result), "
            "(3) preserves any real numbers already present; use '(Please provide: <metric>)' "
            "for missing data \u2014 NEVER fabricate. "
            "Output ONLY JSON: {\"improved_section\": \"...\", \"changes_summary\": \"one short sentence\"}."
        )
        user_msg = ctx_block + (
            f"[Job Description]\n{body.jd_text.strip()}\n\n"
            f"[Section to rewrite]\n{body.section_text.strip()}\n"
        )
        if instr:
            user_msg += f"\n[Extra instructions]\n{instr}\n"
        if body.full_resume and body.full_resume.strip():
            # Truncate to keep prompt small \u2014 we only need surrounding context, not full text.
            ctx = body.full_resume.strip()[:3000]
            user_msg += f"\n[Full resume context \u2014 do NOT rewrite, only reference]\n{ctx}\n"
    else:
        sys_msg = (
            "\u4f60\u662f\u4e13\u4e1a ATS \u7b80\u5386\u7f16\u8f91\u3002\u8bf7\u91cd\u5199\u4e0b\u9762\u7684\u7247\u6bb5\uff0c\u8981\u6c42\uff1a"
            "\uff081\uff09\u7d27\u6263 JD \u91cd\u8981\u5173\u952e\u8bcd\u4e0e\u4ea7\u51fa\uff1b"
            "\uff082\uff09\u7ed3\u5c3e\u5fc5\u987b\u662f\u91cf\u5316\u6210\u679c\u53e5\uff08\u52a8\u4f5c+\u6307\u6807+\u7ed3\u679c\uff09\uff1b"
            "\uff083\uff09\u5df2\u6709\u771f\u5b9e\u6570\u5b57\u4e00\u5b9a\u4fdd\u7559\uff0c\u7f3a\u5931\u6570\u5b57\u5199\u4e3a\u201c\uff08\u8bf7\u8865\u5145\uff1a\u4e8b\u5b9e\u6307\u6807\uff09\u201d\uff0c\u4e0d\u5f97\u6363\u9020\u3002"
            " \u53ea\u8f93\u51fa JSON\uff1a{\"improved_section\": \"...\", \"changes_summary\": \"\u4e00\u53e5\u8bdd\u8bf4\u660e\u6539\u52a8\u91cd\u70b9\"}\u3002"
        )
        user_msg = ctx_block + (
            f"\u3010JD\u3011\n{body.jd_text.strip()}\n\n"
            f"\u3010\u5f85\u91cd\u5199\u7247\u6bb5\u3011\n{body.section_text.strip()}\n"
        )
        if instr:
            user_msg += f"\n\u3010\u989d\u5916\u8981\u6c42\u3011\n{instr}\n"
        if body.full_resume and body.full_resume.strip():
            ctx = body.full_resume.strip()[:3000]
            user_msg += f"\n\u3010\u5168\u6587\u53c2\u8003\u2014\u2014\u52ff\u91cd\u5199\u3011\n{ctx}\n"

    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": sys_msg},
                {"role": "user",   "content": user_msg},
            ],
        )
        raw = (comp.choices[0].message.content or "").strip()
        obj = _try_parse_json(raw) or {}
    except Exception as e:
        if DEBUG: print(f"[section_optimize] error: {e}")
        raise HTTPException(status_code=502, detail="AI service failed; please retry.")

    improved = str(obj.get("improved_section", "")).strip()
    if not improved:
        raise HTTPException(status_code=502, detail="AI returned empty section.")
    return SectionOptimizeResp(
        improved_section=improved,
        changes_summary=str(obj.get("changes_summary", "")).strip(),
    )


# ── Post-fill placeholder refinement ──────────────────────────────────────────
# After the client does a naive [Placeholder] -> "30%" substitution, the resume
# often reads mechanically ("(filled: 30%)" or awkward phrasing). This endpoint
# re-flows ONLY the affected sentences so the numbers feel native to the prose.
@app.post("/v1/resume/refine-placeholders", response_model=RefinePlaceholdersResp)
def refine_placeholders(body: RefinePlaceholdersReq, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if not body.resume_text.strip():
        raise HTTPException(status_code=400, detail="resume_text is required")
    _validate_input_sizes(body.resume_text, body.jd_text or "n/a")

    # Filter fills: drop empty values and obvious junk
    clean_fills = {
        k.strip(): v.strip()
        for k, v in (body.fills or {}).items()
        if k and isinstance(v, str) and v.strip()
    }
    if not clean_fills:
        # Nothing to integrate \u2014 return text as-is so the client can no-op.
        return RefinePlaceholdersResp(refined_resume=body.resume_text, integrated_count=0)

    rl_key = f"refine:{body.user_id or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_OPTIMIZE_PER_HOUR)

    lang = _detect_language(body.jd_text or body.resume_text)
    ctx_block = _build_context_block(body.industry, body.seniority, None, None, lang)
    fills_json = json.dumps(clean_fills, ensure_ascii=False)

    if lang == "en":
        sys_msg = (
            "You are a resume editor. The user just provided real numbers for previously "
            "missing metrics. Your job: rewrite ONLY the sentences that contain those numbers "
            "(or '(Please provide: X)' placeholders that map to them) so the numbers read "
            "naturally as part of a quantified impact statement. "
            "Rules: preserve all other lines verbatim; do not add new bullets; do not invent "
            "additional metrics; never output the literal token '(Please provide:' in the result. "
            "Output ONLY JSON: {\"refined_resume\": \"<full resume text>\"}."
        )
        user_msg = ctx_block + (
            f"[Filled values \u2014 placeholder name : real value]\n{fills_json}\n\n"
            f"[Current resume]\n{body.resume_text.strip()}\n"
        )
    else:
        sys_msg = (
            "\u4f60\u662f\u7b80\u5386\u7f16\u8f91\u3002\u7528\u6237\u521a\u4e3a\u4e4b\u524d\u7f3a\u5931\u7684\u6307\u6807\u586b\u5165\u4e86\u771f\u5b9e\u6570\u5b57\u3002"
            "\u8bf7\u53ea\u91cd\u5199\u542b\u6709\u8fd9\u4e9b\u6570\u5b57\uff08\u6216\u4e0e\u4e4b\u5bf9\u5e94\u7684\u201c\uff08\u8bf7\u8865\u5145\uff1aX\uff09\u201d\u5360\u4f4d\u7b26\uff09\u7684\u53e5\u5b50\uff0c"
            "\u4f7f\u6570\u5b57\u81ea\u7136\u878d\u5165\u91cf\u5316\u6210\u679c\u53e5\u3002"
            " \u89c4\u5219\uff1a\u5176\u4ed6\u884c\u539f\u6837\u4fdd\u7559\uff1b\u4e0d\u65b0\u589e bullet\uff1b\u4e0d\u6363\u9020\u989d\u5916\u6570\u5b57\uff1b"
            "\u7ed3\u679c\u4e2d\u4e0d\u80fd\u51fa\u73b0\u201c\uff08\u8bf7\u8865\u5145\uff1a\u201d\u5b57\u6837\u3002"
            " \u53ea\u8f93\u51fa JSON\uff1a{\"refined_resume\": \"<\u5b8c\u6574\u7b80\u5386\u6587\u672c>\"}\u3002"
        )
        user_msg = ctx_block + (
            f"\u3010\u586b\u5165\u503c\uff1a\u5360\u4f4d\u540d \u2192 \u771f\u5b9e\u6570\u636e\u3011\n{fills_json}\n\n"
            f"\u3010\u5f53\u524d\u7b80\u5386\u3011\n{body.resume_text.strip()}\n"
        )

    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": sys_msg},
                {"role": "user",   "content": user_msg},
            ],
        )
        raw = (comp.choices[0].message.content or "").strip()
        obj = _try_parse_json(raw) or {}
    except Exception as e:
        if DEBUG: print(f"[refine_placeholders] error: {e}")
        # Graceful fallback \u2014 return the input so the user still sees their filled values.
        return RefinePlaceholdersResp(refined_resume=body.resume_text, integrated_count=0)

    refined = str(obj.get("refined_resume", "")).strip()
    # Sanity: never let AI shrink the resume by more than 30% (likely truncation/hallucination).
    if not refined or len(refined) < int(len(body.resume_text) * 0.7):
        return RefinePlaceholdersResp(refined_resume=body.resume_text, integrated_count=0)
    # Strip any leftover '(Please provide:' tokens that the model accidentally kept.
    refined = re.sub(r"\(\s*(Please provide|\u8bf7\u8865\u5145)[^)]*\)", "", refined)
    return RefinePlaceholdersResp(refined_resume=refined, integrated_count=len(clean_fills))


# ===== RETENTION FEATURES =====================================================
# These endpoints turn the product from a one-shot tool into a weekly habit:
#   - /v1/keywords/weave      → dopamine loop (score goes up after each fix)
#   - /v1/interview/questions → users return after applying, to prep for the call
#   - /v1/applications/*      → JD library + pipeline (Teal's whole moat)
#   - /v1/versions/*          → score-progression chart + version diff
# ==============================================================================

# ── Weave missing keywords into the resume ────────────────────────────────────
class WeaveKeywordsReq(BaseModel):
    resume_text: str = Field(..., max_length=20000)
    jd_text: str = Field(..., max_length=10000)
    keywords: List[str] = Field(default_factory=list)
    user_id: Optional[str] = Field(None, max_length=128)

class WeaveKeywordsResp(BaseModel):
    woven_resume: str
    injected_keywords: List[str] = []
    new_match_score: int = 0
    delta: int = 0

@app.post("/v1/keywords/weave", response_model=WeaveKeywordsResp)
def weave_keywords(body: WeaveKeywordsReq, x_api_key: Optional[str] = Header(None)):
    """Take the user's current resume + the keywords they're missing and ask
    the model to weave them in naturally (no keyword stuffing, no fake exp).
    Returns the new resume AND a fresh match score so the UI can show
    "your score went from X → Y" — the core dopamine hook.
    """
    _require_api_key(x_api_key)
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text and jd_text are required")
    keywords = _dedup([k.strip() for k in (body.keywords or []) if k.strip()], 12)
    if not keywords:
        raise HTTPException(status_code=400, detail="at least one keyword required")
    rl_key = f"weave:{body.user_id or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_RETENTION_PER_HOUR)
    lang = _detect_language(body.jd_text)
    sys = (
        "You are an ATS resume editor. Weave the given missing keywords into the "
        "candidate's resume so they appear in plausible, truthful context. "
        "RULES: (1) Never invent employers, titles, dates, or numeric metrics. "
        "(2) Only place a keyword where the existing experience genuinely supports it. "
        "(3) If a keyword cannot be placed honestly, skip it. "
        "(4) Preserve the resume's overall structure, headings, and bullet count. "
        "(5) Output ONLY JSON."
    )
    user_prompt = f"""Resume:
\"\"\"{body.resume_text}\"\"\"

Job Description:
\"\"\"{body.jd_text[:3000]}\"\"\"

Missing keywords to weave (skip any that cannot be placed truthfully):
{json.dumps(keywords, ensure_ascii=False)}

Output strictly this JSON:
{{
  "woven_resume": "<the full updated resume text>",
  "injected_keywords": ["<the subset of keywords you actually placed>"]
}}"""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": sys},
                {"role": "user", "content": user_prompt},
            ],
        )
        obj = _try_parse_json((comp.choices[0].message.content or "").strip()) or {}
    except Exception as e:
        if DEBUG:
            print(f"weave error: {e}")
        raise HTTPException(status_code=502, detail="关键词融入失败，请稍后重试")

    woven = str(obj.get("woven_resume", "")).strip()
    if not woven or len(woven) < int(len(body.resume_text) * 0.7):
        # Safety: never return a truncated resume
        woven = body.resume_text
        injected: List[str] = []
    else:
        injected = _dedup(obj.get("injected_keywords"), 12)

    # Rescore via the same lightweight preview engine for consistency.
    new_score = 0
    try:
        score_prompt = f"""Output ONLY JSON: {{"match_score": <0-100 int>}}.
Resume:
\"\"\"{_resume_focus_excerpt(woven, 2400)}\"\"\"
JD:
\"\"\"{body.jd_text[:2400]}\"\"\""""
        sc = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": score_prompt}],
        )
        new_score = _clamp((_try_parse_json((sc.choices[0].message.content or "").strip()) or {}).get("match_score", 0))
    except Exception as e:
        if DEBUG:
            print(f"weave rescore error: {e}")

    # Best-effort baseline so UI can compute delta even when client didn't pass it.
    old_score = 0
    try:
        score_prompt2 = f"""Output ONLY JSON: {{"match_score": <0-100 int>}}.
Resume:
\"\"\"{_resume_focus_excerpt(body.resume_text, 2400)}\"\"\"
JD:
\"\"\"{body.jd_text[:2400]}\"\"\""""
        sc2 = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": score_prompt2}],
        )
        old_score = _clamp((_try_parse_json((sc2.choices[0].message.content or "").strip()) or {}).get("match_score", 0))
    except Exception:
        pass

    return WeaveKeywordsResp(
        woven_resume=woven,
        injected_keywords=injected,
        new_match_score=new_score,
        delta=max(0, new_score - old_score),
    )


# ── Interview question generator (post-apply retention) ──────────────────────
class InterviewQuestion(BaseModel):
    question: str
    category: str = ""          # "behavioral" | "technical" | "role-specific"
    why_asked: str = ""         # ties back to JD or resume gap
    suggested_answer: str = ""  # STAR-format scaffold using resume facts

class InterviewQReq(BaseModel):
    resume_text: str = Field(..., max_length=20000)
    jd_text: str = Field(..., max_length=10000)
    user_id: Optional[str] = Field(None, max_length=128)

class InterviewQResp(BaseModel):
    questions: List[InterviewQuestion] = []

@app.post("/v1/interview/questions", response_model=InterviewQResp)
def interview_questions(body: InterviewQReq, x_api_key: Optional[str] = Header(None)):
    """Generate 10 likely interview questions for this specific resume × JD,
    each with a suggested STAR answer grounded in the candidate's real
    experience. Brings users back to the app the week after they apply.
    """
    _require_api_key(x_api_key)
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text and jd_text are required")
    rl_key = f"iv:{body.user_id or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_RETENTION_PER_HOUR)
    lang = _detect_language(body.jd_text)
    lang_hint = "Chinese" if lang == "zh" else "English"
    prompt = f"""You are a senior hiring manager who has interviewed 1000+ candidates.
Produce EXACTLY 10 interview questions this candidate is most likely to be asked
for the role described in the JD. Write in {lang_hint}.

For each question, also write:
  - category: one of "behavioral", "technical", "role-specific"
  - why_asked: ONE short sentence tying the question to a gap or strength in the resume vs JD.
  - suggested_answer: a STAR-format scaffold (Situation / Task / Action / Result) of 60–110 words
    that uses ONLY facts present in the resume. Do not invent numbers, employers, or technologies.

Output ONLY JSON: {{"questions": [{{...}} x 10]}}.

Resume:
\"\"\"{_resume_focus_excerpt(body.resume_text, 3500)}\"\"\"

JD:
\"\"\"{body.jd_text[:3000]}\"\"\""""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
        )
        obj = _try_parse_json((comp.choices[0].message.content or "").strip()) or {}
    except Exception as e:
        if DEBUG:
            print(f"interview error: {e}")
        raise HTTPException(status_code=502, detail="生成面试题失败，请稍后重试")
    raw_qs = obj.get("questions") or []
    out: List[InterviewQuestion] = []
    for q in raw_qs[:10]:
        if not isinstance(q, dict):
            continue
        text = str(q.get("question", "")).strip()
        if not text:
            continue
        out.append(InterviewQuestion(
            question=text[:500],
            category=str(q.get("category", "")).strip()[:32] or "behavioral",
            why_asked=str(q.get("why_asked", "")).strip()[:400],
            suggested_answer=str(q.get("suggested_answer", "")).strip()[:1500],
        ))
    return InterviewQResp(questions=out)


# ═════════════════════════════════════════════════════════════════════════════
# DIFFERENTIATION ENDPOINTS — features no competitor (Jobscan/Teal/Rezi) has.
# These are the PR/marketing hooks used to drive social-content virality.
# ═════════════════════════════════════════════════════════════════════════════

# ── HR brutal-review (30-second recruiter screen simulation) ────────────────
class HRReviewReq(BaseModel):
    resume_text: str = Field(..., max_length=20000)
    jd_text: str = Field(..., max_length=10000)
    user_id: Optional[str] = Field(None, max_length=128)

class HRReviewResp(BaseModel):
    verdict: str = ""            # "pass" | "borderline" | "reject"
    first_impression: str = ""   # what HR thinks in first 6 seconds
    red_flags: List[str] = []    # specific issues that would cause rejection
    yellow_flags: List[str] = [] # things that would cause hesitation
    green_signals: List[str] = []# what works in HR's eyes
    questions_hr_would_ask: List[str] = []  # screening call questions
    estimated_callback_rate: int = 0  # 0-100 estimated chance of callback
    blunt_summary: str = ""      # 2-3 sentence honest brutal feedback

@app.post("/v1/hr-review", response_model=HRReviewResp)
def hr_review(body: HRReviewReq, x_api_key: Optional[str] = Header(None)):
    """Simulate a senior HR's 30-second screening decision. Brutal, honest,
    actionable. This is the #1 viral content hook: users screenshot the
    'reject + reasons' and share on social media.

    No competitor offers this. Worth $$$ as standalone feature."""
    _require_api_key(x_api_key)
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text and jd_text are required")
    rl_key = f"hr:{body.user_id or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_RETENTION_PER_HOUR)
    lang = _detect_language(body.jd_text)
    lang_hint = "Chinese" if lang == "zh" else "English"

    prompt = f"""You are a senior HR director at a Fortune 500 company. You have screened 10,000+ resumes.
Your job: simulate your real 30-second screening decision on this resume for this JD.

BE BRUTALLY HONEST. Do NOT be polite. Real HR is harsh. Write in {lang_hint}.

Output ONLY JSON:
{{
  "verdict": "pass" | "borderline" | "reject",
  "first_impression": "<what you think in the first 6 seconds — one sentence, candid>",
  "red_flags": ["specific dealbreakers — e.g. 'No measurable achievements', 'Job-hopping pattern', 'Missing required certification X'"],
  "yellow_flags": ["concerns that would cause hesitation — e.g. 'Gap from 2022-2023 unexplained'"],
  "green_signals": ["specific strengths that catch your eye"],
  "questions_hr_would_ask": ["3-5 screening-call questions you would ask this candidate, in the JD's language"],
  "estimated_callback_rate": <0-100 honest probability this resume gets a callback in a competitive applicant pool>,
  "blunt_summary": "<2-3 sentences of your honest verdict, written as direct feedback to the candidate. Tough love.>"
}}

Resume:
\"\"\"{_resume_focus_excerpt(body.resume_text, 3500)}\"\"\"

JD:
\"\"\"{body.jd_text[:3000]}\"\"\""""

    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL_PREMIUM,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
        )
        obj = _try_parse_json((comp.choices[0].message.content or "").strip()) or {}
    except Exception as e:
        if DEBUG:
            print(f"hr-review error: {e}")
        raise HTTPException(status_code=502, detail="HR评审生成失败，请稍后重试")

    verdict = str(obj.get("verdict", "")).strip().lower()
    if verdict not in ("pass", "borderline", "reject"):
        verdict = "borderline"
    return HRReviewResp(
        verdict=verdict,
        first_impression=str(obj.get("first_impression", "")).strip()[:400],
        red_flags=_dedup(obj.get("red_flags"), 6),
        yellow_flags=_dedup(obj.get("yellow_flags"), 6),
        green_signals=_dedup(obj.get("green_signals"), 6),
        questions_hr_would_ask=_dedup(obj.get("questions_hr_would_ask"), 5),
        estimated_callback_rate=_clamp(obj.get("estimated_callback_rate", 0)),
        blunt_summary=str(obj.get("blunt_summary", "")).strip()[:800],
    )


# ── ATS system parsing simulator (Workday/Greenhouse/Lever/Taleo) ───────────
class ATSSimulateReq(BaseModel):
    resume_text: str = Field(..., max_length=20000)
    jd_text: str = Field("", max_length=10000)
    user_id: Optional[str] = Field(None, max_length=128)

class ATSParseResult(BaseModel):
    ats_name: str                 # "Workday" | "Greenhouse" | "Lever" | "Taleo"
    parse_success_rate: int = 0   # 0-100
    extracted_fields: Dict[str, str] = {}  # what the ATS would extract
    parsing_errors: List[str] = [] # what the ATS would mis-parse
    keyword_match_rate: int = 0   # 0-100 vs JD if jd provided
    will_advance: bool = False    # would this ATS forward to recruiter?
    fix_suggestions: List[str] = []

class ATSSimulateResp(BaseModel):
    results: List[ATSParseResult] = []
    overall_recommendation: str = ""

@app.post("/v1/ats-simulate", response_model=ATSSimulateResp)
def ats_simulate(body: ATSSimulateReq, x_api_key: Optional[str] = Header(None)):
    """Simulate how the 4 major ATS systems (Workday, Greenhouse, Lever, Taleo)
    would parse this resume. Shows extracted fields + parsing errors + match rate.

    No competitor does this per-ATS — Jobscan only shows a generic score."""
    _require_api_key(x_api_key)
    if not body.resume_text.strip():
        raise HTTPException(status_code=400, detail="resume_text is required")
    rl_key = f"ats:{body.user_id or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_RETENTION_PER_HOUR)
    lang = _detect_language(body.jd_text or body.resume_text)
    lang_hint = "Chinese" if lang == "zh" else "English"

    jd_part = f"\n\nJD (for keyword matching):\n\"\"\"{body.jd_text[:2500]}\"\"\"" if body.jd_text.strip() else ""

    prompt = f"""You are an ATS parsing engine emulator. Simulate how 4 major ATS systems
would parse this resume: Workday, Greenhouse, Lever, Taleo.

Each system has known quirks:
- Workday: strict on date formats, struggles with tables & 2-column layouts
- Greenhouse: good at parsing standard formats, weak on PDF graphics
- Lever: simple parser, fails on headers/footers, prefers Reverse-Chrono
- Taleo: oldest, dies on Unicode bullets and complex formatting

Write ALL human-readable text in {lang_hint}.

Output ONLY JSON:
{{
  "results": [
    {{
      "ats_name": "Workday",
      "parse_success_rate": <0-100>,
      "extracted_fields": {{"name": "...", "current_title": "...", "years_experience": "...", "top_skills": "..."}},
      "parsing_errors": ["specific things Workday would misparse"],
      "keyword_match_rate": <0-100 vs JD, 0 if no JD>,
      "will_advance": <true if this ATS would forward to recruiter>,
      "fix_suggestions": ["specific Workday-aware fixes"]
    }},
    {{ "ats_name": "Greenhouse", ... }},
    {{ "ats_name": "Lever", ... }},
    {{ "ats_name": "Taleo", ... }}
  ],
  "overall_recommendation": "<one paragraph summarizing the biggest cross-ATS fix>"
}}

Resume:
\"\"\"{_resume_focus_excerpt(body.resume_text, 3500)}\"\"\"{jd_part}"""

    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL_PREMIUM,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
        )
        obj = _try_parse_json((comp.choices[0].message.content or "").strip()) or {}
    except Exception as e:
        if DEBUG:
            print(f"ats-simulate error: {e}")
        raise HTTPException(status_code=502, detail="ATS模拟失败，请稍后重试")

    raw_results = obj.get("results") or []
    out: List[ATSParseResult] = []
    for r in raw_results[:4]:
        if not isinstance(r, dict):
            continue
        extracted = r.get("extracted_fields") or {}
        if not isinstance(extracted, dict):
            extracted = {}
        # Coerce all values to strings to satisfy Dict[str,str]
        extracted = {str(k)[:64]: str(v)[:400] for k, v in extracted.items() if k}
        out.append(ATSParseResult(
            ats_name=str(r.get("ats_name", "")).strip()[:32] or "Unknown",
            parse_success_rate=_clamp(r.get("parse_success_rate", 0)),
            extracted_fields=extracted,
            parsing_errors=_dedup(r.get("parsing_errors"), 6),
            keyword_match_rate=_clamp(r.get("keyword_match_rate", 0)),
            will_advance=bool(r.get("will_advance", False)),
            fix_suggestions=_dedup(r.get("fix_suggestions"), 5),
        ))
    return ATSSimulateResp(
        results=out,
        overall_recommendation=str(obj.get("overall_recommendation", "")).strip()[:800],
    )


# ── LinkedIn profile optimizer (About + Experience rewrite) ─────────────────
class LinkedInOptimizeReq(BaseModel):
    current_about: str = Field("", max_length=4000)
    current_headline: str = Field("", max_length=400)
    experience_bullets: str = Field("", max_length=8000)  # raw current experience text
    target_role: str = Field("", max_length=200)
    target_industry: str = Field("", max_length=100)
    user_id: Optional[str] = Field(None, max_length=128)

class LinkedInOptimizeResp(BaseModel):
    optimized_headline: str = ""
    optimized_about: str = ""
    optimized_experience: str = ""
    seo_keywords: List[str] = []      # keywords to add for recruiter search
    profile_improvements: List[str] = []  # actionable advice
    estimated_visibility_lift: int = 0  # 0-100 estimated recruiter-search-rank lift

@app.post("/v1/linkedin/optimize", response_model=LinkedInOptimizeResp)
def linkedin_optimize(body: LinkedInOptimizeReq, x_api_key: Optional[str] = Header(None)):
    """Optimize LinkedIn About + Headline + Experience for recruiter searches.

    LinkedIn search ranks profiles by keyword density in About + Headline.
    This endpoint rewrites those sections to maximize discoverability for
    the target role + industry. Extra ARPU: ~$9.99 standalone."""
    _require_api_key(x_api_key)
    if not (body.current_about.strip() or body.experience_bullets.strip()):
        raise HTTPException(status_code=400, detail="At least one of current_about or experience_bullets is required")
    if not body.target_role.strip():
        raise HTTPException(status_code=400, detail="target_role is required")
    rl_key = f"li:{body.user_id or 'anonymous'}"
    _rate_limit_check(rl_key, RATE_LIMIT_OPTIMIZE_PER_HOUR)
    lang = _detect_language(body.current_about or body.experience_bullets or body.target_role)
    lang_hint = "Chinese" if lang == "zh" else "English"

    prompt = f"""You are a top LinkedIn profile strategist. You have optimized 5000+ profiles
that landed recruiter outreach. Rewrite this user's LinkedIn for the target role.

WRITE EVERYTHING IN {lang_hint}.

Goals:
1. Headline (≤220 chars): role + value prop + 2-3 high-search keywords
2. About (~1500-2000 chars): hook in first 220 chars (LinkedIn truncates), then story, then keywords, then CTA
3. Experience: rewrite each bullet with strong verbs + quantified outcomes; embed target-role keywords
4. SEO keywords: list 10-15 high-value keywords recruiters would search for this role
5. Profile improvements: 3-5 immediate actions beyond text (photo, banner, featured section, etc.)

Output ONLY JSON:
{{
  "optimized_headline": "<≤220 chars>",
  "optimized_about": "<1500-2000 chars, first line MUST be the strongest hook>",
  "optimized_experience": "<full rewritten experience block, multi-line plain text>",
  "seo_keywords": ["keyword1", ...],
  "profile_improvements": ["actionable step 1", ...],
  "estimated_visibility_lift": <0-100 honest estimate of recruiter-search rank lift>
}}

Target role: {body.target_role[:200]}
Target industry: {body.target_industry[:100] or 'any'}

Current headline: {body.current_headline[:400] or '(none)'}

Current About:
\"\"\"{body.current_about[:2000] or '(none)'}\"\"\"

Current Experience:
\"\"\"{body.experience_bullets[:4000] or '(none)'}\"\"\""""

    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL_PREMIUM,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
        )
        obj = _try_parse_json((comp.choices[0].message.content or "").strip()) or {}
    except Exception as e:
        if DEBUG:
            print(f"linkedin error: {e}")
        raise HTTPException(status_code=502, detail="LinkedIn优化失败，请稍后重试")

    return LinkedInOptimizeResp(
        optimized_headline=str(obj.get("optimized_headline", "")).strip()[:240],
        optimized_about=str(obj.get("optimized_about", "")).strip()[:2400],
        optimized_experience=str(obj.get("optimized_experience", "")).strip()[:8000],
        seo_keywords=_dedup(obj.get("seo_keywords"), 15),
        profile_improvements=_dedup(obj.get("profile_improvements"), 6),
        estimated_visibility_lift=_clamp(obj.get("estimated_visibility_lift", 0)),
    )


# ── Job application tracker (JD library + pipeline) ──────────────────────────
ALLOWED_APP_STATUS = {"saved", "applied", "interview", "offer", "rejected", "archived"}

class JobApplicationIn(BaseModel):
    user_id: str = Field(..., max_length=128)
    company: str = Field("", max_length=200)
    role: str = Field("", max_length=200)
    jd_text: str = Field("", max_length=10000)
    status: str = Field("saved", max_length=32)
    match_score: int = Field(0, ge=0, le=100)
    notes: str = Field("", max_length=4000)

class JobApplicationOut(BaseModel):
    id: int
    user_id: str
    company: str
    role: str
    jd_text: str
    status: str
    match_score: int
    notes: str
    created_at: int
    updated_at: int

class JobApplicationPatch(BaseModel):
    company: Optional[str] = Field(None, max_length=200)
    role: Optional[str] = Field(None, max_length=200)
    jd_text: Optional[str] = Field(None, max_length=10000)
    status: Optional[str] = Field(None, max_length=32)
    match_score: Optional[int] = Field(None, ge=0, le=100)
    notes: Optional[str] = Field(None, max_length=4000)


def _row_to_app(r: Any) -> JobApplicationOut:
    return JobApplicationOut(
        id=int(r["id"]),
        user_id=str(r["user_id"]),
        company=str(r["company"] or ""),
        role=str(r["role"] or ""),
        jd_text=str(r["jd_text"] or ""),
        status=str(r["status"] or "saved"),
        match_score=int(r["match_score"] or 0),
        notes=str(r["notes"] or ""),
        created_at=int(r["created_at"]),
        updated_at=int(r["updated_at"]),
    )


@app.post("/v1/applications", response_model=JobApplicationOut)
def create_application(body: JobApplicationIn, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if body.status and body.status not in ALLOWED_APP_STATUS:
        raise HTTPException(status_code=400, detail=f"status must be one of {sorted(ALLOWED_APP_STATUS)}")
    now = int(time.time())
    with _db_lock:
        conn = get_db()
        cur = conn.execute(
            "INSERT INTO job_applications (user_id, company, role, jd_text, status, match_score, notes, created_at, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (body.user_id, body.company, body.role, body.jd_text, body.status, body.match_score, body.notes, now, now),
        )
        new_id = cur.lastrowid
        conn.commit()
        row = conn.execute(
            "SELECT id, user_id, company, role, jd_text, status, match_score, notes, created_at, updated_at "
            "FROM job_applications WHERE id = ?", (new_id,)
        ).fetchone()
        conn.close()
    _log_user_event(body.user_id, "application_saved", {"status": body.status or "saved"})
    return _row_to_app(row)


@app.get("/v1/applications", response_model=List[JobApplicationOut])
def list_applications(user_id: str, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if not user_id:
        raise HTTPException(status_code=400, detail="user_id required")
    with _db_lock:
        conn = get_db()
        rows = conn.execute(
            "SELECT id, user_id, company, role, jd_text, status, match_score, notes, created_at, updated_at "
            "FROM job_applications WHERE user_id = ? ORDER BY updated_at DESC LIMIT 200",
            (user_id,),
        ).fetchall()
        conn.close()
    return [_row_to_app(r) for r in rows]


@app.patch("/v1/applications/{app_id}", response_model=JobApplicationOut)
def update_application(app_id: int, body: JobApplicationPatch, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if body.status is not None and body.status not in ALLOWED_APP_STATUS:
        raise HTTPException(status_code=400, detail=f"status must be one of {sorted(ALLOWED_APP_STATUS)}")
    with _db_lock:
        conn = get_db()
        row = conn.execute(
            "SELECT id, user_id, company, role, jd_text, status, match_score, notes, created_at, updated_at "
            "FROM job_applications WHERE id = ?", (app_id,)
        ).fetchone()
        if not row:
            conn.close()
            raise HTTPException(status_code=404, detail="application not found")
        new_company = body.company if body.company is not None else row["company"]
        new_role    = body.role    if body.role    is not None else row["role"]
        new_jd      = body.jd_text if body.jd_text is not None else row["jd_text"]
        new_status  = body.status  if body.status  is not None else row["status"]
        new_score   = body.match_score if body.match_score is not None else row["match_score"]
        new_notes   = body.notes   if body.notes   is not None else row["notes"]
        now = int(time.time())
        conn.execute(
            "UPDATE job_applications SET company=?, role=?, jd_text=?, status=?, match_score=?, notes=?, updated_at=? WHERE id=?",
            (new_company, new_role, new_jd, new_status, new_score, new_notes, now, app_id),
        )
        conn.commit()
        row = conn.execute(
            "SELECT id, user_id, company, role, jd_text, status, match_score, notes, created_at, updated_at "
            "FROM job_applications WHERE id = ?", (app_id,)
        ).fetchone()
        conn.close()
    _log_user_event(str(row["user_id"]), "application_status_updated", {"status": str(row["status"] or "")})
    return _row_to_app(row)


@app.delete("/v1/applications/{app_id}")
def delete_application(app_id: int, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    with _db_lock:
        conn = get_db()
        conn.execute("DELETE FROM job_applications WHERE id = ?", (app_id,))
        conn.commit()
        conn.close()
    return {"ok": True}


# ── Resume version history (score-progression chart + diff) ──────────────────
class ResumeVersionIn(BaseModel):
    user_id: str = Field(..., max_length=128)
    application_id: Optional[int] = None
    label: str = Field("", max_length=120)
    resume_text: str = Field(..., max_length=40000)
    match_score: int = Field(0, ge=0, le=100)

class ResumeVersionOut(BaseModel):
    id: int
    user_id: str
    application_id: Optional[int] = None
    label: str
    resume_text: str
    match_score: int
    created_at: int


def _row_to_version(r: Any) -> ResumeVersionOut:
    return ResumeVersionOut(
        id=int(r["id"]),
        user_id=str(r["user_id"]),
        application_id=(int(r["application_id"]) if r["application_id"] is not None else None),
        label=str(r["label"] or ""),
        resume_text=str(r["resume_text"] or ""),
        match_score=int(r["match_score"] or 0),
        created_at=int(r["created_at"]),
    )


@app.post("/v1/versions", response_model=ResumeVersionOut)
def create_version(body: ResumeVersionIn, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if not body.resume_text.strip():
        raise HTTPException(status_code=400, detail="resume_text required")
    now = int(time.time())
    with _db_lock:
        conn = get_db()
        cur = conn.execute(
            "INSERT INTO resume_versions (user_id, application_id, label, resume_text, match_score, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (body.user_id, body.application_id, body.label, body.resume_text, body.match_score, now),
        )
        new_id = cur.lastrowid
        conn.commit()
        row = conn.execute(
            "SELECT id, user_id, application_id, label, resume_text, match_score, created_at "
            "FROM resume_versions WHERE id = ?", (new_id,)
        ).fetchone()
        conn.close()
    _log_user_event(body.user_id, "version_saved", {"match_score": int(body.match_score or 0)})
    return _row_to_version(row)


@app.get("/v1/versions", response_model=List[ResumeVersionOut])
def list_versions(user_id: str, application_id: Optional[int] = None, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if not user_id:
        raise HTTPException(status_code=400, detail="user_id required")
    with _db_lock:
        conn = get_db()
        if application_id is not None:
            rows = conn.execute(
                "SELECT id, user_id, application_id, label, resume_text, match_score, created_at "
                "FROM resume_versions WHERE user_id = ? AND application_id = ? ORDER BY created_at ASC LIMIT 50",
                (user_id, application_id),
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT id, user_id, application_id, label, resume_text, match_score, created_at "
                "FROM resume_versions WHERE user_id = ? ORDER BY created_at DESC LIMIT 50",
                (user_id,),
            ).fetchall()
        conn.close()
    return [_row_to_version(r) for r in rows]


@app.get("/healthz")
def healthz():
    # Also verify DB is reachable so load balancers / uptime monitors catch storage failures.
    db_ok = True
    try:
        conn = get_db()
        conn.execute("SELECT 1")
        conn.close()
    except Exception:
        db_ok = False
    return {"ok": db_ok, "model": OPENAI_MODEL, "version": "2.0.0", "db": "ok" if db_ok else "error"}


    mrr = sum(
        sub_counts.get(plan, 0) * monthly
        for plan, monthly in PLAN_MONTHLY_REVENUE.items()
    )
    arr = mrr * 12

    # ── Conversion rate ────────────────────────────────────────────────────
    total_paying = sum(sub_counts.values())
    conversion_pct = round(total_paying / total_users * 100, 2) if total_users else 0.0

    return {
        "generated_at": now,
        "users": {
            "total": total_users,
            "last_30d": users_30d,
            "last_7d":  users_7d,
            "last_24h": users_24h,
        },
        "optimizations": {
            "total":    total_opts,
            "last_30d": opts_30d,
            "last_7d":  opts_7d,
            "per_user_30d": round(opts_30d / users_30d, 2) if users_30d else 0,
        },
        "subscriptions": {
            "active_by_plan": sub_counts,
            "total_active":   total_paying,
            "total_ever_paid": total_ever_paid,
            "new_last_30d":   new_subs_30d,
        },
        "revenue": {
            "mrr_usd":  round(mrr, 2),
            "arr_usd":  round(arr, 2),
            "conversion_pct": conversion_pct,
        },
        "infra": {
            "db_backend": "postgresql" if DB_IS_PG else "sqlite",
            "db_sslmode": DB_SSLMODE if DB_IS_PG else "n/a",
            "db_pool": {
                "min": DB_POOL_MIN_CONN,
                "max": DB_POOL_MAX_CONN,
            },
            "db_require_pg_in_prod": DB_REQUIRE_PG_IN_PROD,
            "billing_mode": "insecure_dev" if ALLOW_INSECURE_BILLING else "play_verified",
            "model": OPENAI_MODEL,
        },
    }


@app.get("/v1/admin/launch-readiness", response_model=LaunchReadinessResp)
def admin_launch_readiness(x_api_key: Optional[str] = Header(None)):
    """Single-pane launch gate report for production go/no-go decisions."""
    _require_api_key(x_api_key)
    now = int(time.time())

    db_ok = True
    try:
        with _db_lock:
            conn = get_db()
            try:
                conn.execute("SELECT 1")
            finally:
                conn.close()
    except Exception:
        db_ok = False

    quality = _compute_quality_snapshot(limit=200)
    retention = _compute_retention_overview(days=30, sample_limit=1000)

    gates = [
        ReadinessGateOut(
            name="db_reachable",
            passed=db_ok,
            value=1.0 if db_ok else 0.0,
            threshold=1.0,
            note="数据库必须可连通并可查询关键表",
        ),
        ReadinessGateOut(
            name="quality_risky_rate",
            passed=float(quality.get("risky_rate", 1.0)) <= LAUNCH_GATE_RISKY_RATE_MAX,
            value=float(quality.get("risky_rate", 1.0)),
            threshold=LAUNCH_GATE_RISKY_RATE_MAX,
            note=f"优化高风险占比需 <= {LAUNCH_GATE_RISKY_RATE_MAX:.2f}",
        ),
        ReadinessGateOut(
            name="placeholder_residue_rate",
            passed=float(quality.get("placeholder_residue_rate", 1.0)) <= LAUNCH_GATE_PLACEHOLDER_RATE_MAX,
            value=float(quality.get("placeholder_residue_rate", 1.0)),
            threshold=LAUNCH_GATE_PLACEHOLDER_RATE_MAX,
            note=f"导出前占位符残留率需 <= {LAUNCH_GATE_PLACEHOLDER_RATE_MAX:.2f}",
        ),
        ReadinessGateOut(
            name="retention_rate_7d",
            passed=float(retention.get("retention_rate_7d", 0.0)) >= LAUNCH_GATE_RETENTION_7D_MIN,
            value=float(retention.get("retention_rate_7d", 0.0)),
            threshold=LAUNCH_GATE_RETENTION_7D_MIN,
            note=f"7日留存需 >= {LAUNCH_GATE_RETENTION_7D_MIN:.2f} 才适合放量",
        ),
    ]

    ready = all(g.passed for g in gates)
    return LaunchReadinessResp(
        ready=ready,
        generated_at=now,
        gates=gates,
        metrics={
            "quality": quality,
            "retention": retention,
            "model": OPENAI_MODEL,
            "db_backend": "postgresql" if DB_IS_PG else "sqlite",
            "db_sslmode": DB_SSLMODE if DB_IS_PG else "n/a",
            "db_require_pg_in_prod": DB_REQUIRE_PG_IN_PROD,
        },
    )


@app.get("/v1/admin/reliability", response_model=ReliabilityOverviewResp)
def admin_reliability(
    hours: int = Query(24, ge=1, le=168),
    limit: int = Query(100, ge=20, le=500),
    x_api_key: Optional[str] = Header(None),
):
    """Operational reliability dashboard: incidents + optimize latency signal."""
    _require_api_key(x_api_key)
    now = int(time.time())
    since = now - hours * 3600
    with _db_lock:
        conn = get_db()
        try:
            rows = conn.execute(
                """SELECT event_type, severity, detail_json, created_at
                   FROM reliability_events
                   WHERE created_at >= ?
                   ORDER BY created_at DESC
                   LIMIT ?""",
                (since, limit),
            ).fetchall()
        finally:
            conn.close()

    events: List[ReliabilityEventOut] = []
    optimize_latencies: List[float] = []
    optimize_success_count = 0
    optimize_error_count = 0
    err = 0
    warn = 0

    for r in rows:
        detail_obj = _try_parse_json(r["detail_json"] or "{}") or {}
        ev = ReliabilityEventOut(
            event_type=str(r["event_type"] or ""),
            severity=str(r["severity"] or "info"),
            detail=detail_obj,
            created_at=int(r["created_at"] or 0),
        )
        events.append(ev)

        if ev.severity == "error":
            err += 1
        elif ev.severity == "warning":
            warn += 1

        if ev.event_type in ("optimize_sync_success", "optimize_async_success"):
            optimize_success_count += 1
            dur = detail_obj.get("duration_ms")
            if isinstance(dur, (int, float)):
                optimize_latencies.append(float(dur))
        if ev.event_type in ("optimize_sync_exception", "optimize_async_exception"):
            optimize_error_count += 1

    return ReliabilityOverviewResp(
        hours=hours,
        total_events=len(events),
        error_events=err,
        warning_events=warn,
        optimize_success_count=optimize_success_count,
        optimize_error_count=optimize_error_count,
        optimize_p95_ms=round(_p95(optimize_latencies), 2),
        latest=events,
    )


@app.post("/v1/growth/attribution")
def growth_attribution(body: GrowthAttributionReq, x_api_key: Optional[str] = Header(None)):
    """Capture paid/organic acquisition attribution for growth funnel analysis."""
    _require_api_key(x_api_key)
    uid = (body.user_id or "").strip()
    if not uid:
        raise HTTPException(status_code=400, detail="user_id is required")
    _log_user_event(
        uid,
        "acquisition_touch",
        {
            "source": body.source,
            "medium": body.medium or "",
            "campaign": body.campaign or "",
            "channel": body.channel or "",
            "landing_path": body.landing_path or "",
        },
    )
    return {"ok": True}


@app.get("/v1/admin/growth/funnel", response_model=GrowthFunnelResp)
def admin_growth_funnel(
    days: int = Query(30, ge=7, le=180),
    x_api_key: Optional[str] = Header(None),
):
    """Top-line acquisition -> optimize -> paid funnel for traffic campaigns."""
    _require_api_key(x_api_key)
    now = int(time.time())
    since = now - days * 86400
    with _db_lock:
        conn = get_db()
        try:
            touched = conn.execute(
                "SELECT COUNT(DISTINCT user_id) AS c FROM user_events WHERE event_name='acquisition_touch' AND created_at>=?",
                (since,),
            ).fetchone()
            optimized = conn.execute(
                """SELECT COUNT(DISTINCT r.user_id) AS c
                   FROM records r
                   JOIN (
                     SELECT DISTINCT user_id
                     FROM user_events
                     WHERE event_name='acquisition_touch' AND created_at>=?
                   ) t ON t.user_id = r.user_id
                   WHERE r.created_at>=?""",
                (since, since),
            ).fetchone()
            paid = conn.execute(
                """SELECT COUNT(DISTINCT e.user_id) AS c
                   FROM entitlements e
                   JOIN (
                     SELECT DISTINCT user_id
                     FROM user_events
                     WHERE event_name='acquisition_touch' AND created_at>=?
                   ) t ON t.user_id = e.user_id
                   WHERE e.is_active=1 AND e.created_at>=?""",
                (since, since),
            ).fetchone()
        finally:
            conn.close()

    touched_users = int((touched["c"] if touched else 0) or 0)
    optimize_users = int((optimized["c"] if optimized else 0) or 0)
    paid_users = int((paid["c"] if paid else 0) or 0)

    t2o = round((optimize_users / touched_users), 4) if touched_users else 0.0
    o2p = round((paid_users / optimize_users), 4) if optimize_users else 0.0
    t2p = round((paid_users / touched_users), 4) if touched_users else 0.0

    return GrowthFunnelResp(
        days=days,
        touched_users=touched_users,
        optimize_users=optimize_users,
        paid_users=paid_users,
        touch_to_optimize=t2o,
        optimize_to_paid=o2p,
        touch_to_paid=t2p,
    )


@app.post("/v1/admin/quality/repair-placeholders", response_model=PlaceholderRepairResp)
def admin_repair_placeholders(
    limit: int = Query(500, ge=50, le=5000),
    x_api_key: Optional[str] = Header(None),
):
    """One-time repair of historical placeholder residue to improve export quality consistency."""
    _require_api_key(x_api_key)
    with _db_lock:
        conn = get_db()
        try:
            rows = conn.execute(
                "SELECT id, optimized_text FROM records ORDER BY created_at DESC LIMIT ?",
                (limit,),
            ).fetchall()
            repaired = 0
            remaining = 0
            for r in rows:
                rid = int(r["id"])
                txt = r["optimized_text"] or ""
                if _placeholder_residue_count(txt) <= 0:
                    continue
                lang = _detect_language(txt)
                new_txt = _sanitize_placeholder_tokens(txt, lang=lang)
                conn.execute("UPDATE records SET optimized_text=? WHERE id=?", (new_txt, rid))
                repaired += 1
            conn.commit()

            check_rows = conn.execute(
                "SELECT optimized_text FROM records ORDER BY created_at DESC LIMIT ?",
                (limit,),
            ).fetchall()
            for row in check_rows:
                if _placeholder_residue_count(row["optimized_text"] or "") > 0:
                    remaining += 1
        finally:
            conn.close()

    _log_reliability_event(
        "quality_placeholder_repair",
        "info",
        {"limit": limit, "repaired": repaired, "remaining": remaining},
    )
    return PlaceholderRepairResp(scanned=limit, repaired=repaired, remaining_with_placeholders=remaining)


@app.get("/v1/admin/valuation")
def admin_valuation(target_price_usd: int = 80_000, x_api_key: Optional[str] = Header(None)):
    """Owner-only valuation estimator used for sellability tracking."""
    _require_api_key(x_api_key)
    now = int(time.time())
    conn = get_db()
    try:
        active_subs = conn.execute(
            "SELECT plan, COUNT(*) as cnt FROM entitlements WHERE is_active = 1 AND expires_at > ? GROUP BY plan",
            (now,),
        ).fetchall()
        sub_counts = {row[0]: row[1] for row in active_subs}
        opts_30d = conn.execute(
            "SELECT COUNT(*) FROM records WHERE created_at >= ?",
            (now - 30 * 86400,),
        ).fetchone()[0] or 0
    finally:
        conn.close()

    plan_monthly_revenue = {
        "monthly": 8.49,
        "annual": 4.96,
        "one_time": 0.0,
    }
    mrr = sum(sub_counts.get(plan, 0) * monthly for plan, monthly in plan_monthly_revenue.items())
    arr = mrr * 12
    low = mrr * 24
    base = mrr * 36
    high = mrr * 48
    gap_to_target = max(0.0, float(target_price_usd) - base)
    required_mrr_for_target = {
        "at_24x": round(target_price_usd / 24.0, 2),
        "at_36x": round(target_price_usd / 36.0, 2),
        "at_48x": round(target_price_usd / 48.0, 2),
    }
    return {
        "target_sale_price_usd": int(target_price_usd),
        "current": {
            "mrr_usd": round(mrr, 2),
            "arr_usd": round(arr, 2),
            "optimizations_last_30d": int(opts_30d),
            "active_subscriptions": sub_counts,
        },
        "valuation_range_usd": {
            "low_24x": round(low, 2),
            "base_36x": round(base, 2),
            "high_48x": round(high, 2),
        },
        "gap": {
            "to_target_at_36x_usd": round(gap_to_target, 2),
            "required_mrr_for_target": required_mrr_for_target,
        },
        "recommendations": [
            "Increase paid conversion with export lock and clear score-delta proof.",
            "Sustain stable MRR for 3-6 months before listing on acquisition marketplaces.",
            "Drive repeat usage with LinkedIn optimization and ATS simulation upsells.",
        ],
    }


@app.get("/v1/admin/quality/audit", response_model=OptimizeQualityAuditResp)
def admin_quality_audit(
    limit: int = Query(100, ge=10, le=500),
    risky_only: bool = Query(False),
    x_api_key: Optional[str] = Header(None),
):
    """Audit real optimization quality from stored records.

    Purpose: detect "looks improved but still weak" cases using deterministic
    text signals (similarity, growth, quantified coverage, weak-verb residue).
    """
    _require_api_key(x_api_key)
    with _db_lock:
        conn = get_db()
        try:
            rows = conn.execute(
                """SELECT id, user_id, created_at, resume_text, optimized_text, before_total, after_total
                   FROM records ORDER BY created_at DESC LIMIT ?""",
                (limit,),
            ).fetchall()
        finally:
            conn.close()

    items: List[OptimizeQualityAuditItem] = []
    for r in rows:
        signals = _opt_quality_signals(r["resume_text"] or "", r["optimized_text"] or "")
        item = OptimizeQualityAuditItem(
            record_id=int(r["id"]),
            user_id=str(r["user_id"] or ""),
            created_at=int(r["created_at"] or 0),
            before_total=int(r["before_total"] or 0),
            after_total=int(r["after_total"] or 0),
            score_delta=max(0, int(r["after_total"] or 0) - int(r["before_total"] or 0)),
            similarity=float(signals["similarity"]),
            growth_pct=float(signals["growth_pct"]),
            quantified_bullet_ratio=float(signals["quantified_bullet_ratio"]),
            weak_verb_hits=int(signals["weak_verb_hits"]),
            risky=bool(signals["risky"]),
            issues=list(signals["issues"]),
        )
        if risky_only and not item.risky:
            continue
        items.append(item)

    total = len(items)
    risky_count = len([x for x in items if x.risky])
    avg_similarity = round(sum(x.similarity for x in items) / total, 4) if total else 0.0
    avg_growth_pct = round(sum(x.growth_pct for x in items) / total, 2) if total else 0.0
    avg_score_delta = round(sum(x.score_delta for x in items) / total, 2) if total else 0.0
    avg_quant = round(sum(x.quantified_bullet_ratio for x in items) / total, 2) if total else 0.0

    return OptimizeQualityAuditResp(
        total=total,
        risky_count=risky_count,
        risky_rate=round((risky_count / total), 4) if total else 0.0,
        avg_similarity=avg_similarity,
        avg_growth_pct=avg_growth_pct,
        avg_score_delta=avg_score_delta,
        avg_quantified_ratio=avg_quant,
        items=items,
    )

# ===== Stripe Web Billing =====================================================
# Set env vars to activate:
#   STRIPE_SECRET_KEY       — sk_live_xxx / sk_test_xxx
#   STRIPE_WEBHOOK_SECRET   — whsec_xxx  (from Stripe Dashboard > Webhooks)
#   STRIPE_PRICE_ONE_TIME   — price_xxx  (7-day pass / short window)
#   STRIPE_PRICE_MONTHLY    — price_xxx  (Monthly, secondary)
#   STRIPE_PRICE_ANNUAL     — price_xxx  (Annual, secondary)
#   STRIPE_SUCCESS_URL      — https://cvdoor.com/static/app.html?payment=success
#   STRIPE_CANCEL_URL       — https://cvdoor.com/static/app.html?payment=cancel
# ==============================================================================

STRIPE_SECRET_KEY     = os.getenv("STRIPE_SECRET_KEY", "").strip()
STRIPE_WEBHOOK_SECRET = os.getenv("STRIPE_WEBHOOK_SECRET", "").strip()
STRIPE_PRICE_ONE_TIME = os.getenv("STRIPE_PRICE_ONE_TIME", "").strip()
STRIPE_PRICE_MONTHLY  = os.getenv("STRIPE_PRICE_MONTHLY", "").strip()
STRIPE_PRICE_ANNUAL   = os.getenv("STRIPE_PRICE_ANNUAL", "").strip()
STRIPE_SUCCESS_URL    = os.getenv("STRIPE_SUCCESS_URL", "https://cvdoor.com/static/app.html?payment=success").strip()
STRIPE_CANCEL_URL     = os.getenv("STRIPE_CANCEL_URL",  "https://cvdoor.com/static/app.html?payment=cancel").strip()


class StripeCheckoutReq(BaseModel):
    plan: str = Field(..., pattern=r"^(one_time|monthly|annual)$")
    user_id: str = Field(..., max_length=128)


@app.post("/v1/billing/stripe/checkout")
def stripe_checkout(body: StripeCheckoutReq, x_api_key: Optional[str] = Header(None)):
    """Create a Stripe Checkout session and return the redirect URL.

    Client redirects to `url`; Stripe collects payment and redirects back to
    STRIPE_SUCCESS_URL with ?session_id=xxx. The webhook (below) then grants Pro.
    """
    _require_api_key(x_api_key)
    if not STRIPE_SECRET_KEY:
        raise HTTPException(status_code=503, detail="Stripe not configured on server. Contact support@cvdoor.app to upgrade.")
    price_map = {
        "one_time": STRIPE_PRICE_ONE_TIME,
        "monthly": STRIPE_PRICE_MONTHLY,
        "annual": STRIPE_PRICE_ANNUAL,
    }
    price_id = price_map.get(body.plan, "")
    if not price_id:
        raise HTTPException(status_code=503, detail=f"Stripe price for '{body.plan}' not configured")
    try:
        import stripe as _stripe  # optional dependency
        _stripe.api_key = STRIPE_SECRET_KEY
        checkout_mode = "payment" if body.plan == "one_time" else "subscription"
        session = _stripe.checkout.Session.create(
            payment_method_types=["card"],
            line_items=[{"price": price_id, "quantity": 1}],
            mode=checkout_mode,
            success_url=STRIPE_SUCCESS_URL + "&session_id={CHECKOUT_SESSION_ID}",
            cancel_url=STRIPE_CANCEL_URL,
            metadata={"user_id": body.user_id, "plan": body.plan},
            client_reference_id=body.user_id,
        )
        return {"url": session.url}
    except ImportError:
        raise HTTPException(status_code=503, detail="stripe package not installed on server")
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Stripe error: {e}")


@app.post("/v1/billing/stripe/webhook")
async def stripe_webhook(request: Request):
    """Handle Stripe webhook events.

    Must be registered in Stripe Dashboard > Developers > Webhooks pointing to
    https://api.cvdoor.com/v1/billing/stripe/webhook.
    Events to listen for: checkout.session.completed, customer.subscription.deleted.
    """
    if not STRIPE_WEBHOOK_SECRET:
        raise HTTPException(status_code=503, detail="Webhook secret not configured")
    payload = await request.body()
    sig = request.headers.get("stripe-signature", "")
    try:
        import stripe as _stripe
        _stripe.api_key = STRIPE_SECRET_KEY
        event = _stripe.Webhook.construct_event(payload, sig, STRIPE_WEBHOOK_SECRET)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

    etype = event.get("type", "")
    now = int(time.time())

    if etype == "checkout.session.completed":
        session = event["data"]["object"]
        user_id = (session.get("metadata") or {}).get("user_id", "").strip()
        plan_label = (session.get("metadata") or {}).get("plan", "monthly")
        sub_id = session.get("subscription", "")
        payment_id = session.get("payment_intent", "")
        if user_id and (sub_id or payment_id):
            duration_sec = 7 * 86400 if plan_label == "one_time" else (366 * 86400 if plan_label == "annual" else 31 * 86400)
            expires_at = now + duration_sec
            product_id = f"stripe_{plan_label}"
            purchase_token = sub_id or payment_id
            auto_renewing = 0 if plan_label == "one_time" else 1
            with _db_lock:
                conn = get_db()
                try:
                    conn.execute(
                        """INSERT INTO entitlements
                               (user_id, product_id, purchase_token, order_id, plan,
                                expires_at, auto_renewing, is_active, created_at, updated_at)
                           VALUES (?,?,?,?,?,?,?,1,?,?)
                           ON CONFLICT(user_id, product_id) DO UPDATE SET
                               purchase_token = excluded.purchase_token,
                               order_id       = excluded.order_id,
                               plan           = excluded.plan,
                               expires_at     = excluded.expires_at,
                               auto_renewing  = excluded.auto_renewing,
                               is_active      = 1,
                               updated_at     = excluded.updated_at""",
                        (user_id, product_id, purchase_token,
                         session.get("payment_intent", ""), plan_label,
                         expires_at, auto_renewing, now, now),
                    )
                    conn.commit()
                finally:
                    conn.close()

    elif etype == "customer.subscription.deleted":
        sub = event["data"]["object"]
        sub_id = sub.get("id", "")
        if sub_id:
            with _db_lock:
                conn = get_db()
                try:
                    conn.execute(
                        "UPDATE entitlements SET is_active=0, updated_at=? WHERE purchase_token=?",
                        (now, sub_id),
                    )
                    conn.commit()
                finally:
                    conn.close()

    return {"received": True}




def _log_user_event(user_id: str, event_name: str, event_meta: Optional[Dict[str, Any]] = None) -> None:
    if not user_id or not event_name:
        return
    now = int(time.time())
    payload = json.dumps(event_meta or {}, ensure_ascii=False)
    with _db_lock:
        conn = get_db()
        try:
            conn.execute(
                "INSERT INTO user_events (user_id, event_name, event_meta, created_at) VALUES (?, ?, ?, ?)",
                (user_id.strip(), event_name.strip()[:64], payload, now),
            )
            conn.commit()
        finally:
            conn.close()


def _compute_retention_health(user_id: str) -> RetentionHealthResp:
    now = int(time.time())
    day_7 = now - 7 * 86400
    day_30 = now - 30 * 86400

    with _db_lock:
        conn = get_db()
        try:
            last_opt_row = conn.execute(
                "SELECT MAX(created_at) as ts FROM records WHERE user_id=?",
                (user_id,),
            ).fetchone()
            last_evt_row = conn.execute(
                "SELECT MAX(created_at) as ts FROM user_events WHERE user_id=?",
                (user_id,),
            ).fetchone()
            opt_30d_row = conn.execute(
                "SELECT COUNT(*) as c FROM records WHERE user_id=? AND created_at>=?",
                (user_id, day_30),
            ).fetchone()
            ver_30d_row = conn.execute(
                "SELECT COUNT(*) as c FROM resume_versions WHERE user_id=? AND created_at>=?",
                (user_id, day_30),
            ).fetchone()
            evt_7d_row = conn.execute(
                "SELECT COUNT(*) as c FROM user_events WHERE user_id=? AND created_at>=?",
                (user_id, day_7),
            ).fetchone()
            apps_rows = conn.execute(
                "SELECT status, COUNT(*) as c FROM job_applications WHERE user_id=? GROUP BY status",
                (user_id,),
            ).fetchall()
        finally:
            conn.close()

    last_opt = int(last_opt_row["ts"] or 0) if last_opt_row else 0
    last_evt = int(last_evt_row["ts"] or 0) if last_evt_row else 0
    last_active_ts = max(last_opt, last_evt)
    last_active_days = int((now - last_active_ts) / 86400) if last_active_ts else 999

    optimize_30d = int(opt_30d_row["c"] or 0) if opt_30d_row else 0
    versions_30d = int(ver_30d_row["c"] or 0) if ver_30d_row else 0
    events_7d = int(evt_7d_row["c"] or 0) if evt_7d_row else 0
    apps_by_status = {str(r["status"]): int(r["c"] or 0) for r in apps_rows}
    active_pipeline = sum(apps_by_status.get(s, 0) for s in ("saved", "applied", "interview"))

    risk = 0
    if last_active_days >= 14:
        risk += 45
    elif last_active_days >= 7:
        risk += 30
    elif last_active_days >= 3:
        risk += 18
    else:
        risk += 5
    if optimize_30d == 0:
        risk += 25
    elif optimize_30d <= 1:
        risk += 14
    if versions_30d == 0:
        risk += 8
    if active_pipeline == 0:
        risk += 12
    if events_7d == 0:
        risk += 10
    risk = _clamp(risk, 0, 100)

    if risk >= 70:
        stage = "at_risk"
    elif risk >= 45:
        stage = "slipping"
    elif optimize_30d >= 2 and last_active_days <= 3:
        stage = "active"
    else:
        stage = "new"

    actions: List[str] = []
    if optimize_30d == 0:
        actions.append("先完成一次完整职位定制优化，建立第一份高匹配版本。")
    if active_pipeline == 0:
        actions.append("新增至少1个目标职位到Job Tracker，形成持续回访动机。")
    if versions_30d < 2:
        actions.append("针对同一职位至少保存2个版本，做版本对比后再投递。")
    if events_7d == 0:
        actions.append("本周至少完成一次“段落精修”或“关键词融入”微动作。")
    if last_active_days >= 7:
        actions.append("建议立即回访：更新最新JD并重新生成简历+求职信。")

    return RetentionHealthResp(
        user_id=user_id,
        churn_risk_score=risk,
        retention_stage=stage,
        last_active_days_ago=max(0, min(999, last_active_days)),
        metrics={
            "optimize_30d": optimize_30d,
            "versions_30d": versions_30d,
            "events_7d": events_7d,
            "active_pipeline_count": active_pipeline,
            "applications_by_status": apps_by_status,
        },
        next_best_actions=_dedup(actions, 5),
    )


@app.post("/v1/retention/event")
def retention_event(body: RetentionEventReq, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if not body.user_id.strip():
        raise HTTPException(status_code=400, detail="user_id is required")
    _log_user_event(body.user_id, body.event_name, body.event_meta)
    return {"ok": True}


@app.get("/v1/retention/health/{user_id}", response_model=RetentionHealthResp)
def retention_health(user_id: str, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    if not user_id.strip():
        raise HTTPException(status_code=400, detail="user_id is required")
    return _compute_retention_health(user_id.strip())

@app.get("/v1/admin/retention", response_model=RetentionOverviewResp)
def admin_retention(
    days: int = Query(30, ge=7, le=180),
    sample_limit: int = Query(1000, ge=50, le=5000),
    x_api_key: Optional[str] = Header(None),
):
    _require_api_key(x_api_key)
    overview = _compute_retention_overview(days=days, sample_limit=sample_limit)
    return RetentionOverviewResp(**overview)


@app.get("/v1/admin/retention/reactivation-queue", response_model=ReactivationQueueResp)
def admin_reactivation_queue(
    days: int = Query(60, ge=14, le=180),
    sample_limit: int = Query(1500, ge=100, le=5000),
    max_items: int = Query(100, ge=10, le=500),
    x_api_key: Optional[str] = Header(None),
):
    """Actionable list of users to re-engage first, ranked by churn risk."""
    _require_api_key(x_api_key)
    now = int(time.time())
    since = now - days * 86400
    with _db_lock:
        conn = get_db()
        try:
            rows = conn.execute(
                """SELECT DISTINCT user_id FROM records WHERE created_at>=?
                   ORDER BY created_at DESC LIMIT ?""",
                (since, sample_limit),
            ).fetchall()
        finally:
            conn.close()

    user_ids = [str(r["user_id"]) for r in rows if str(r["user_id"]).strip()]
    items: List[ReactivationCandidateOut] = []
    for uid in user_ids:
        h = _compute_retention_health(uid)
        if h.retention_stage not in ("slipping", "at_risk"):
            continue
        items.append(
            ReactivationCandidateOut(
                user_id=uid,
                churn_risk_score=int(h.churn_risk_score),
                retention_stage=h.retention_stage,
                last_active_days_ago=int(h.last_active_days_ago),
                next_best_actions=_dedup(list(h.next_best_actions or []), 3),
            )
        )
    items.sort(key=lambda x: (x.churn_risk_score, x.last_active_days_ago), reverse=True)
    return ReactivationQueueResp(total_candidates=len(items), items=items[:max_items])

@app.get("/")
def root():
    import os as _os
    _static = _os.path.join(_os.path.dirname(__file__), "static", "index.html")
    if _os.path.isfile(_static):
        return FileResponse(_static)
    return {"service": "CVATS.AI", "version": "2.0.0"}

# ===== Static web files (landing page + web app) =====
# Mounted LAST so API routes always take precedence.
_static_dir = os.path.join(os.path.dirname(__file__), "static")
if os.path.isdir(_static_dir):
    app.mount("/static", StaticFiles(directory=_static_dir), name="static_assets")



"""
CVDoor / CVATS.AI  Backend  v2.0.0
Endpoints:
  POST /v1/optimize
  POST /v1/auth/session
  GET  /v1/records?limit=
  DELETE /v1/records/{id}
  POST /v1/records/clear
  GET  /healthz
"""
from fastapi import FastAPI, HTTPException, Query, Header
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from openai import OpenAI
import os, json, traceback, time, sqlite3, threading, re, difflib, base64, hmac, hashlib

# ===== 环境 =====
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
if not OPENAI_API_KEY:
    raise RuntimeError("环境变量 OPENAI_API_KEY 未设置")

OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
DEBUG = os.getenv("DEBUG", "1") == "1"
DB_PATH = os.getenv("DB_PATH", "cvdoor.db")
SERVER_API_KEY = os.getenv("SERVER_API_KEY", "")
SESSION_TOKEN_SECRET = os.getenv("SESSION_TOKEN_SECRET", OPENAI_API_KEY)
SESSION_TOKEN_TTL_SEC = int(os.getenv("SESSION_TOKEN_TTL_SEC", "86400"))
CORS_ALLOWED_ORIGINS = [
    x.strip() for x in os.getenv("CORS_ALLOWED_ORIGINS", "https://cvdoor.app").split(",") if x.strip()
]

client = OpenAI(api_key=OPENAI_API_KEY)

# Cover letter generation heuristics:
# - keep enough resume context for personalization without exploding prompt size
# - ensure final cover letter has at least substantial body length
MAX_RESUME_CONTEXT_CHARS = 3500
RESUME_EXCERPT_HEAD_LINES = 10
RESUME_EXCERPT_TAIL_LINES = 10
MIN_COVER_LETTER_LENGTH = 180
MIN_COVER_LETTER_QUALITY_SCORE = int(os.getenv("MIN_COVER_LETTER_QUALITY_SCORE", "75"))

# ===== SQLite =====
_db_lock = threading.Lock()

def get_db():
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    with _db_lock:
        conn = get_db()
        conn.execute("""
        CREATE TABLE IF NOT EXISTS records (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
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
        conn.execute("""
        CREATE TABLE IF NOT EXISTS quality_failures (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            reason         TEXT    NOT NULL,
            quality_json   TEXT    NOT NULL,
            required_info  TEXT    NOT NULL DEFAULT '[]',
            resume_excerpt TEXT    NOT NULL,
            jd_excerpt     TEXT    NOT NULL,
            cover_letter   TEXT    NOT NULL,
            created_at     INTEGER NOT NULL
        )""")
        conn.execute("""
        CREATE TABLE IF NOT EXISTS regression_cases (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
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
        conn.commit()
        conn.close()

init_db()

# ===== IO Models =====
class OptimizeReq(BaseModel):
    resume_text: str
    jd_text: str
    user_id: Optional[str] = None
    style: Optional[str] = None
    industry: Optional[str] = None
    seniority: Optional[str] = None
    region: Optional[str] = None
    tone: Optional[str] = None

class SessionCreateReq(BaseModel):
    user_id: str

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

class OptimizeResp(BaseModel):
    optimized: str
    before_total: int = Field(ge=0, le=100)
    after_total:  int = Field(ge=0, le=100)
    dims_before: List[int] = []
    dims_after:  List[int] = []
    match_score: Optional[int] = None
    added_keywords: List[str] = []
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

# ===== FastAPI =====
app = FastAPI(title="CVATS.AI Backend", version="2.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=CORS_ALLOWED_ORIGINS,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-API-Key"],
)

# ===== Prompt =====
SYSTEM_PROMPT = """你是资深 ATS 简历优化专家兼专业求职信撰写师。

你的任务：
1. 分析【简历】与【职位JD】的匹配度，优化简历内容以增加 ATS 通过率
2. 同时生成一份专业、针对性强的求职信，体现应聘者与该岗位的完美契合

输出格式：必须只输出 JSON（无其他文本），包含以下字段：

{
  "optimized": "<完整优化后的简历（保留格式）>",
  "before_total": <0-100整数>,
  "after_total": <0-100整数>,
  "dims_before": [<关键词0-100>, <经验0-100>, <技能0-100>, <格式0-100>, <成就0-100>, <表达0-100>],
  "dims_after": [<同上6个优化后分数>],
  "added_keywords": ["<提取的关键词>", ...],
  "cover_letter": "<专业英文求职信，250-300词。必须包含：(1)对目标公司和职位的深入理解，(2)突出应聘者的核心优势与JD关键技能的映射，(3)2-3个量化的成就示例，(4)对角色和公司的真挚兴趣与承诺>",
  "analysis": {
    "overall": {
      "summary": "<2-3句总结>",
      "strengths": ["<优势1>", "<优势2>", ...],
      "issues": ["<问题1>", "<问题2>", ...],
      "actions": ["<建议1>", "<建议2>", ...]
    },
    "dimensions": [
      {"name": "Keywords", "before": <0-100>, "after": <0-100>, "reasons": [...], "problems": [...], "suggestions": [...], "missing_before": [...], "added_after": [...]},
      {"name": "Experience", ...},
      {"name": "Skills", ...},
      {"name": "Format", ...},
      {"name": "Impact", ...},
      {"name": "Clarity", ...}
    ]
  }
}

关键要求：
- optimized 必须是“明显优化后”的版本，不可只做同义替换；要对经历 bullets 结构、动词、关键词、成果表达做实质增强
- 每段经历（至少 3 段，若原文不足则按实际）最后一句必须是“量化成果句”
- 量化成果句格式：动作 + 指标 + 结果，例如“通过X，使Y提升Z%”
- 若原文没有真实数字，不可捏造；请写成“（请补充：xx指标数字）”的量化占位提示，指导用户补齐
- analysis.overall.actions 必须包含至少 1 条“如何把经历改成量化表达”的可执行建议
- cover_letter 必须是真实、高质量的英文求职信，不要生成占位符或模板
- 求职信要充分利用简历中的成就数据，展现量化的影响力
- 确保 JSON 格式完全有效，无转义错误
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
    if (x_api_key or "").strip() != SERVER_API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")

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
    too_similar = sim >= 0.90
    not_quantified_enough = total >= 3 and q < max(2, total // 3)
    return too_similar or not_quantified_enough

def _rewrite_resume_with_quantification(resume: str, jd: str, draft: str) -> str:
    prompt = f"""你是顶级中文简历优化顾问。请重写下面的“优化稿”，输出最终可投递简历正文（纯文本，不要JSON）。

硬性要求：
1) 明显优于原文，且与JD强相关。
2) 每段经历最后一句必须为“量化成果句”（动作+指标+结果）。
3) 如果缺少真实数字，不能编造；请使用“（请补充：某指标数字）”形式提示用户补齐。
4) 强化ATS关键词匹配，避免空话。
5) 保持专业、简洁、可读。

【原始简历】
{resume}

【职位JD】
{jd}

【当前优化稿】
{draft}
"""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            messages=[
                {"role": "system", "content": "你只输出最终简历正文，不要解释。"},
                {"role": "user", "content": prompt},
            ],
        )
        return (comp.choices[0].message.content or "").strip()
    except Exception:
        return ""

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


def _force_quantified_bullets(text: str) -> str:
    lines = (text or "").splitlines()
    out = []
    for line in lines:
        stripped = line.strip()
        if _is_bullet_line(stripped) and not _has_metric(stripped):
            out.append(line.rstrip() + "（请补充：该项成果指标数字）")
        else:
            out.append(line)
    return "\n".join(out).strip()

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


def _call_gpt(resume: str, jd: str) -> dict:
    for use_json_mode in (True, False):
        kwargs = dict(
            model=OPENAI_MODEL,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": _build_user_msg(resume, jd)},
            ],
        )
        if use_json_mode:
            kwargs["response_format"] = {"type": "json_object"}
        try:
            comp = client.chat.completions.create(**kwargs)
            raw = (comp.choices[0].message.content or "").strip()
            if DEBUG:
                print(f"\n=== GPT raw (json_mode={use_json_mode}) ===\n{raw[:500]}\n")
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
) -> str:
    normalized_style = _normalize_cover_letter_style(style)
    normalized_tone = _normalize_cover_letter_tone(tone)
    prompt = f"""你是专业求职信撰写专家。基于输入生成英文求职信（250-320词）。
必须包含：岗位理解、技能映射、2-3个量化成就、动机、Dear/Sincerely结构。
避免模板化空话，必须具体。

【简历摘要】
{_resume_focus_excerpt(resume)}

【JD】
{jd}

【匹配提炼(JSON)】
{json.dumps(brief, ensure_ascii=False)}

【风格】{normalized_style}
【语气强度】{normalized_tone}
只输出求职信正文。"""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            messages=[{"role": "user", "content": prompt}],
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
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
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
    prompt = f"""请基于反馈重写以下英文求职信（250-320词），保持真实、具体、结构完整。
反馈：
{json.dumps(feedback, ensure_ascii=False)}

【JD】{jd}
【简历摘要】{_resume_focus_excerpt(resume)}
【原始求职信】{letter}
【风格】{_normalize_cover_letter_style(style)}
【语气】{_normalize_cover_letter_tone(tone)}
只输出重写后的求职信正文。"""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            messages=[{"role": "user", "content": prompt}],
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
) -> tuple[str, Dict[str, Any]]:
    """两阶段生成求职信：提炼卖点 -> 起草 -> 质量评审与一次重写"""
    normalized_style = _normalize_cover_letter_style(style)
    normalized_tone = _normalize_cover_letter_tone(tone)
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
    )
    quality = _evaluate_cover_letter_quality(cover_letter, resume, jd)
    if _cover_letter_needs_retry(cover_letter) or quality.get("overall", 0) < MIN_COVER_LETTER_QUALITY_SCORE:
        rewritten = _rewrite_cover_letter_once(
            letter=cover_letter,
            resume=resume,
            jd=jd,
            feedback=quality.get("feedback") or ["请增强与JD要求的逐项映射。", "请补充量化成果。"],
            style=normalized_style,
            tone=normalized_tone,
        )
        if len(rewritten or "") >= MIN_COVER_LETTER_LENGTH:
            cover_letter = rewritten
            quality = _evaluate_cover_letter_quality(cover_letter, resume, jd)
    if DEBUG and cover_letter:
        print(f"\n=== Cover Letter Generated ({quality.get('overall', 0)}) ===\n{cover_letter[:300]}\n")
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
        cur = conn.execute(
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
        record_id = cur.lastrowid
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

# ===== Routes =====
@app.post("/v1/optimize", response_model=OptimizeResp)
def optimize(body: OptimizeReq):
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text 和 jd_text 不能为空")
    try:
        obj = _call_gpt(body.resume_text, body.jd_text)
        if not obj:
            raise HTTPException(status_code=500, detail="AI 返回空响应")

        resp = _parse_response(obj)
        if _needs_resume_rewrite(body.resume_text, resp.optimized):
            rewritten = _rewrite_resume_with_quantification(body.resume_text, body.jd_text, resp.optimized)
            if rewritten:
                resp.optimized = rewritten
                resp.after_total = max(resp.after_total, min(100, resp.before_total + 8))
                resp.dims_after = [max(a, min(100, b + 5)) for a, b in zip(resp.dims_after, resp.dims_before)]

        resp.optimized = _force_quantified_bullets(resp.optimized)
        resp.analysis = _build_suggestion_tiers(_ensure_quant_actions(resp.analysis))

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

        return resp

    except HTTPException:
        raise
    except Exception as e:
        print("ERROR:", repr(e))
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"AI 优化失败：{e}")

@app.post("/v1/auth/session", response_model=SessionResp)
def create_session_token(body: SessionCreateReq, x_api_key: Optional[str] = Header(None)):
    if not body.user_id.strip():
        raise HTTPException(status_code=400, detail="user_id 不能为空")
    if not SERVER_API_KEY:
        raise HTTPException(status_code=503, detail="Session auth is not configured on server")
    if (x_api_key or "").strip() != SERVER_API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")
    token, exp = _issue_session_token(body.user_id.strip())
    return SessionResp(session_token=token, expires_at=exp)

@app.post("/v1/generate-cover-letter")
def generate_cover_letter(body: OptimizeReq):
    """单独生成求职信（用于"重新生成"功能）"""
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text 和 jd_text 不能为空")
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
            raise HTTPException(status_code=500, detail="AI 未能生成求职信")
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
        raise HTTPException(status_code=500, detail=f"求职信生成失败：{e}")

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

@app.post("/v1/quality/regression-cases", response_model=RegressionCaseOut)
def upsert_regression_case(body: RegressionCaseIn, x_api_key: Optional[str] = Header(None)):
    _require_api_key(x_api_key)
    now = int(time.time())
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
            cur = conn.execute(
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
            case_id = cur.lastrowid
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

@app.get("/healthz")
def healthz():
    return {"ok": True, "model": OPENAI_MODEL, "version": "2.0.0"}

@app.get("/")
def root():
    return {"service": "CVATS.AI", "version": "2.0.0"}

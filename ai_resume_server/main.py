"""
CVDoor / CVATS.AI  Backend  v2.0.0
Endpoints:
  POST /v1/optimize
  GET  /v1/records?user_id=&limit=
  DELETE /v1/records/{id}?user_id=
  POST /v1/records/clear?user_id=
  GET  /healthz
"""
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from typing import List, Optional
from openai import OpenAI
import os, json, traceback, time, sqlite3, threading, re, difflib

# ===== 環境 =====
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
if not OPENAI_API_KEY:
    raise RuntimeError("環境變量 OPENAI_API_KEY 未設置")

OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
DEBUG = os.getenv("DEBUG", "1") == "1"
DB_PATH = os.getenv("DB_PATH", "cvdoor.db")

client = OpenAI(api_key=OPENAI_API_KEY)

# Cover letter generation heuristics:
# - keep enough resume context for personalization without exploding prompt size
# - ensure final cover letter has at least substantial body length
MAX_RESUME_CONTEXT_CHARS = 3500
RESUME_EXCERPT_HEAD_LINES = 10
RESUME_EXCERPT_TAIL_LINES = 10
MIN_COVER_LETTER_LENGTH = 180

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
        conn.commit()
        conn.close()

init_db()

# ===== IO Models =====
class OptimizeReq(BaseModel):
    resume_text: str
    jd_text: str
    user_id: Optional[str] = None
    style: Optional[str] = None

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
    analysis: Optional[AnalysisOut] = None
    record_id:  Optional[int] = None
    created_at: Optional[int] = None

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

# ===== FastAPI =====
app = FastAPI(title="CVATS.AI Backend", version="2.0.0")
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"]
)

# ===== Prompt =====
SYSTEM_PROMPT = """你是資深 ATS 簡歷優化專家兼專業求職信撰寫師。

你的任務：
1. 分析【簡歷】與【職位JD】的匹配度，優化簡歷內容以增加 ATS 通過率
2. 同時生成一份專業、針對性強的求職信，體現應聘者與該崗位的完美契合

輸出格式：必須只輸出 JSON（無其他文本），包含以下字段：

{
  "optimized": "<完整優化後的簡歷（保留格式）>",
  "before_total": <0-100整數>,
  "after_total": <0-100整數>,
  "dims_before": [<關鍵詞0-100>, <經驗0-100>, <技能0-100>, <格式0-100>, <成就0-100>, <表達0-100>],
  "dims_after": [<同上6個優化後分數>],
  "added_keywords": ["<提取的關鍵詞>", ...],
  "cover_letter": "<專業英文求職信，250-300詞。必須包含：(1)對目標公司和職位的深入理解，(2)突出應聘者的核心優勢與JD關鍵技能的映射，(3)2-3個量化的成就示例，(4)對角色和公司的真摯興趣與承諾>",
  "analysis": {
    "overall": {
      "summary": "<2-3句總結>",
      "strengths": ["<優勢1>", "<優勢2>", ...],
      "issues": ["<問題1>", "<問題2>", ...],
      "actions": ["<建議1>", "<建議2>", ...]
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

關鍵要求：
- optimized 必須是“明顯優化後”的版本，不可只做同義替換；要對經歷 bullets 結構、動詞、關鍵詞、成果表達做實質增強
- 每段經歷（至少 3 段，若原文不足則按實際）最後一句必須是“量化成果句”
- 量化成果句格式：動作 + 指標 + 結果，例如“通過X，使Y提升Z%”
- 若原文沒有真實數字，不可捏造；請寫成“（請補充：xx指標數字）”的量化佔位提示，指導用戶補齊
- analysis.overall.actions 必須包含至少 1 條“如何把經歷改成量化表達”的可執行建議
- cover_letter 必須是真實、高質量的英文求職信，不要生成佔位符或模板
- 求職信要充分利用簡歷中的成就數據，展現量化的影響力
- 確保 JSON 格式完全有效，無轉義錯誤
"""

def _build_user_msg(resume: str, jd: str) -> str:
    return f"【簡歷】\n{resume.strip()}\n\n【職位JD】\n{jd.strip()}"

def _normalize_cover_letter_style(style: Optional[str]) -> str:
    allowed = {"professional", "natural", "brief"}
    s = (style or "").strip().lower()
    return s if s in allowed else "professional"

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
        analysis = AnalysisOut(
            overall=OverallAnalysisOut(
                summary=str(raw_o.get("summary", "")),
                strengths=_dedup(raw_o.get("strengths"), 5),
                issues=_dedup(raw_o.get("issues"), 5),
                actions=_dedup(raw_o.get("actions"), 5),
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
_METRIC_RE = re.compile(r"(\d|%|％|x|倍|HK\$|\$|¥|人|名|個|次|小時|天|周|月|年)")
_PLACEHOLDER_RE = re.compile(
    r"\[(?:請補充|待補充|待填寫|to be filled|tbd|company|position|metric|數字)[\w\s\-:：，,]*\]",
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
    prompt = f"""你是頂級中文簡歷優化顧問。請重寫下面的“優化稿”，輸出最終可投遞簡歷正文（純文本，不要JSON）。

硬性要求：
1) 明顯優於原文，且與JD強相關。
2) 每段經歷最後一句必須爲“量化成果句”（動作+指標+結果）。
3) 如果缺少真實數字，不能編造；請使用“（請補充：某指標數字）”形式提示用戶補齊。
4) 強化ATS關鍵詞匹配，避免空話。
5) 保持專業、簡潔、可讀。

【原始簡歷】
{resume}

【職位JD】
{jd}

【當前優化稿】
{draft}
"""
    try:
        comp = client.chat.completions.create(
            model=OPENAI_MODEL,
            messages=[
                {"role": "system", "content": "你只輸出最終簡歷正文，不要解釋。"},
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
    quant_action = "把每段經歷最後一句改成量化成果：動作 + 指標 + 結果（如：將到課率從A提升到B，+C%）。"
    if not any(("量化" in a) or ("指標" in a) or ("%" in a) for a in actions):
        actions.insert(0, quant_action)
    analysis.overall.actions = _dedup(actions, 5)
    return analysis


def _force_quantified_bullets(text: str) -> str:
    lines = (text or "").splitlines()
    out = []
    for line in lines:
        stripped = line.strip()
        if _is_bullet_line(stripped) and not _has_metric(stripped):
            out.append(line.rstrip() + "（請補充：該項成果指標數字）")
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
    # 補充兜底：用於識別未被佔位符正則覆蓋的英文模板殘留片段
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

def _generate_cover_letter_only(resume: str, jd: str, style: str = "professional") -> str:
    """專門生成求職信（用於"重新生成"功能）"""
    normalized_style = _normalize_cover_letter_style(style)
    resume_context = _resume_focus_excerpt(resume)
    cover_letter_prompt = f"""你是專業求職信撰寫專家。根據以下信息生成一份高質量的英文求職信。

【簡歷】
{resume_context}

【職位JD】
{jd}

【風格】
{normalized_style}（可選值：professional=正式版, natural=自然版, brief=簡短版）

要求：
1. 250-300詞的求職信
2. 突出符合JD要求的核心優勢
3. 包含2-3個量化的成就或具體例子
4. 展現對公司和職位的深入理解
5. 必須是完整的有格式的英文求職信，包含Dear/Sincerely等結構

直接輸出求職信內容，不要包含任何其他文本或說明。"""

    for use_json_mode in (True, False):
        kwargs = dict(
            model=OPENAI_MODEL,
            messages=[
                {"role": "user", "content": cover_letter_prompt},
            ],
        )
        if not use_json_mode:  # Cover letter 不需要 JSON 格式
            try:
                comp = client.chat.completions.create(**kwargs)
                cover_letter = (comp.choices[0].message.content or "").strip()
                if len(cover_letter) >= MIN_COVER_LETTER_LENGTH:
                    if DEBUG:
                        print(f"\n=== Cover Letter Generated ===\n{cover_letter[:300]}\n")
                    return cover_letter
            except Exception as e:
                if DEBUG:
                    print(f"Cover letter generation error: {e}")
    
    return ""

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
        raise HTTPException(status_code=400, detail="resume_text 和 jd_text 不能爲空")
    try:
        obj = _call_gpt(body.resume_text, body.jd_text)
        if not obj:
            raise HTTPException(status_code=500, detail="AI 返回空響應")

        resp = _parse_response(obj)
        if _needs_resume_rewrite(body.resume_text, resp.optimized):
            rewritten = _rewrite_resume_with_quantification(body.resume_text, body.jd_text, resp.optimized)
            if rewritten:
                resp.optimized = rewritten
                resp.after_total = max(resp.after_total, min(100, resp.before_total + 8))
                resp.dims_after = [max(a, min(100, b + 5)) for a, b in zip(resp.dims_after, resp.dims_before)]

        resp.optimized = _force_quantified_bullets(resp.optimized)
        resp.analysis = _ensure_quant_actions(resp.analysis)

        # 補齊 Cover Letter：優先沿用主調用結果，缺失或質量不足時才補調一次
        # 僅當 _call_gpt 主調用結果明顯缺失或存在模板化/佔位痕跡時，才重新生成，避免覆蓋已生成的高質量版本。
        needs_cover_letter_retry = _cover_letter_needs_retry(resp.cover_letter)
        if needs_cover_letter_retry:
            try:
                cover_letter = _generate_cover_letter_only(resp.optimized, body.jd_text, body.style or "professional")
                if len(cover_letter) >= MIN_COVER_LETTER_LENGTH:
                    resp.cover_letter = cover_letter
            except Exception as e:
                if DEBUG:
                    print(f"Cover letter generation during optimize failed: {e}")
                # 不中斷主流程，Cover Letter 生成失敗不影響簡歷優化結果

        if body.user_id and body.user_id.strip():
            record_id, created_at = _save_record(
                body.user_id.strip(), body.resume_text, body.jd_text, resp
            )
            resp.record_id = record_id
            resp.created_at = created_at

        return resp

    except HTTPException:
        raise
    except Exception as e:
        print("ERROR:", repr(e))
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"AI 優化失敗：{e}")

@app.post("/v1/generate-cover-letter")
def generate_cover_letter(body: OptimizeReq):
    """單獨生成求職信（用於"重新生成"功能）"""
    if not body.resume_text.strip() or not body.jd_text.strip():
        raise HTTPException(status_code=400, detail="resume_text 和 jd_text 不能爲空")
    try:
        cover_letter = _generate_cover_letter_only(body.resume_text, body.jd_text, body.style or "professional")
        if not cover_letter:
            raise HTTPException(status_code=500, detail="AI 未能生成求職信")
        return {"cover_letter": cover_letter}
    except HTTPException:
        raise
    except Exception as e:
        print("ERROR generating cover letter:", repr(e))
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"求職信生成失敗：{e}")

@app.get("/v1/records", response_model=List[RecordOut])
def list_records(
    user_id: str = Query(...),
    limit: int = Query(50, ge=1, le=200)
):
    with _db_lock:
        conn = get_db()
        rows = conn.execute(
            "SELECT * FROM records WHERE user_id=? ORDER BY created_at DESC LIMIT ?",
            (user_id, limit)
        ).fetchall()
        conn.close()
    return [_row_to_record(r) for r in rows]

@app.delete("/v1/records/{record_id}")
def delete_record(record_id: int, user_id: str = Query(...)):
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
def clear_records(user_id: str = Query(...)):
    with _db_lock:
        conn = get_db()
        conn.execute("DELETE FROM records WHERE user_id=?", (user_id,))
        conn.commit()
        conn.close()
    return {"ok": True}

@app.get("/healthz")
def healthz():
    return {"ok": True, "model": OPENAI_MODEL, "version": "2.0.0"}

@app.get("/")
def root():
    return {"service": "CVATS.AI", "version": "2.0.0"}

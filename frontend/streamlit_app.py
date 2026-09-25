"""AI Forex Trading Bot - Mobile Monitoring & Guardrail Hub.

Mobile-first Streamlit dashboard that pivots away from manual trade execution and
static technical indicators toward:
  * Risk guardrail configuration (max risk %, allowed-symbols watchlist, drawdown
    stop limits) that bound an autonomous LLM trading agent.
  * A real-time AI activity console streaming the LLM's market analysis, reasoning
    and BUY / SELL / HOLD decision states.
  * Live telemetry cards (balance, equity, open P&L) streamed from the backend.

Run with:
    streamlit run frontend/streamlit_app.py
"""

from __future__ import annotations

import os
import time
from typing import Any
from urllib.parse import quote, unquote

import requests
import streamlit as st

# --- Page config MUST be the first Streamlit call. ---
st.set_page_config(
    page_title="AI Forex Trading Bot",
    page_icon="📈",
    layout="wide",
    initial_sidebar_state="expanded",
)


def _inject_theme() -> None:
    """Professional dark trading-desk look & feel."""
    st.markdown(
        """
        <style>
        :root {
            --accent: #00d09c;
            --accent-2: #2f9bff;
            --accent-red: #f6465d;
            --accent-amber: #f0b90b;
            --bg: #070b12;
            --bg-2: #0b1220;
            --panel: #121a27;
            --panel-2: #18222f;
            --border: #223047;
            --border-soft: #1a2436;
            --muted: #93a1b5;
            --text: #eef2f8;
        }

        /* Layered, professional trading-desk background:
           deep navy base + soft accent glows + faint grid pattern. */
        .stApp {
            background:
              radial-gradient(1100px 520px at 12% -8%, rgba(47,155,255,0.10) 0%, rgba(47,155,255,0) 60%),
              radial-gradient(1000px 500px at 100% 0%, rgba(0,208,156,0.10) 0%, rgba(0,208,156,0) 55%),
              radial-gradient(900px 600px at 50% 120%, rgba(47,155,255,0.06) 0%, rgba(47,155,255,0) 60%),
              linear-gradient(180deg, var(--bg-2) 0%, var(--bg) 100%);
            background-attachment: fixed;
        }
        /* Faint grid overlay for a "market terminal" feel. */
        .stApp::before {
            content: "";
            position: fixed; inset: 0; pointer-events: none; z-index: 0;
            background-image:
              linear-gradient(rgba(255,255,255,0.018) 1px, transparent 1px),
              linear-gradient(90deg, rgba(255,255,255,0.018) 1px, transparent 1px);
            background-size: 42px 42px;
            mask-image: radial-gradient(circle at 50% 30%, #000 0%, transparent 80%);
        }
        .block-container { position: relative; z-index: 1; }

        #MainMenu, footer {visibility: hidden;}
        header[data-testid="stHeader"] { background: transparent; }
        [data-testid="collapsedControl"] { display: block !important; visibility: visible !important; z-index: 1000; }
        [data-testid="stSidebarCollapsedControl"] { display: block !important; visibility: visible !important; z-index: 1000; }
        .block-container { padding-top: 1.2rem; padding-bottom: 3rem; max-width: 1280px; }

        /* Headings */
        h1, h2, h3, h4 { color: var(--text); font-weight: 700; letter-spacing: -0.01em; }
        h2, h3 { position: relative; padding-left: 12px; }
        h2::before, h3::before {
            content: ""; position: absolute; left: 0; top: 0.15em; bottom: 0.15em;
            width: 4px; border-radius: 4px;
            background: linear-gradient(180deg, var(--accent) 0%, var(--accent-2) 100%);
        }
        .stCaption, .st-emotion-cache-1 .stMarkdown p { color: var(--muted); }

        /* Cards / metrics — glassmorphic depth */
        div[data-testid="stMetric"] {
            background: linear-gradient(180deg, rgba(24,34,47,0.92) 0%, rgba(18,26,39,0.92) 100%);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 18px 20px;
            box-shadow: 0 8px 28px rgba(0,0,0,0.40), inset 0 1px 0 rgba(255,255,255,0.03);
            backdrop-filter: blur(6px);
            transition: transform .15s ease, border-color .15s ease, box-shadow .15s ease;
        }
        div[data-testid="stMetric"]:hover {
            transform: translateY(-2px);
            border-color: rgba(0,208,156,0.45);
            box-shadow: 0 12px 34px rgba(0,0,0,0.5), 0 0 0 1px rgba(0,208,156,0.15);
        }
        div[data-testid="stMetricLabel"] p { color: var(--muted); font-size: 0.74rem; text-transform: uppercase; letter-spacing: 0.10em; }
        div[data-testid="stMetricValue"] { color: var(--text); font-weight: 800; font-variant-numeric: tabular-nums; }

        /* Buttons */
        .stButton > button, .stFormSubmitButton > button {
            border-radius: 12px;
            border: 1px solid var(--border);
            background: linear-gradient(180deg, var(--panel-2) 0%, var(--panel) 100%);
            color: var(--text);
            font-weight: 600;
            padding: 0.5rem 0.9rem;
            transition: all .15s ease;
        }
        .stButton > button:hover, .stFormSubmitButton > button:hover {
            border-color: var(--accent);
            color: var(--accent);
            box-shadow: 0 0 0 3px rgba(0,208,156,0.12);
            transform: translateY(-1px);
        }
        .stFormSubmitButton > button {
            background: linear-gradient(90deg, var(--accent) 0%, #00b487 100%);
            color: #04120d; border: none;
            box-shadow: 0 6px 18px rgba(0,208,156,0.28);
        }
        .stFormSubmitButton > button:hover { color: #04120d; filter: brightness(1.06); box-shadow: 0 8px 22px rgba(0,208,156,0.4); }
        .stButton > button[kind="primary"] {
            background: linear-gradient(90deg, var(--accent) 0%, var(--accent-2) 100%);
            color: #04120d; border: none; box-shadow: 0 6px 18px rgba(0,208,156,0.28);
        }
        button:disabled, .stButton > button:disabled { opacity: 0.45 !important; filter: grayscale(0.3); }

        /* Inputs */
        .stTextInput input, .stNumberInput input, .stTextArea textarea,
        div[data-baseweb="select"] > div {
            background: rgba(9,13,20,0.75) !important;
            border: 1px solid var(--border) !important;
            border-radius: 10px !important;
            color: var(--text) !important;
        }
        .stTextInput input:focus, .stNumberInput input:focus {
            border-color: var(--accent) !important;
            box-shadow: 0 0 0 3px rgba(0,208,156,0.12) !important;
        }

        /* Sidebar — panelled with a soft edge glow */
        section[data-testid="stSidebar"] {
            background: linear-gradient(180deg, #0c121c 0%, #090e16 100%);
            border-right: 1px solid var(--border);
            box-shadow: 8px 0 24px rgba(0,0,0,0.35);
        }
        section[data-testid="stSidebar"] .stForm {
            border: 1px solid var(--border); border-radius: 14px; padding: 10px 12px;
            background: linear-gradient(180deg, rgba(18,26,39,0.9) 0%, rgba(12,18,28,0.9) 100%);
            box-shadow: inset 0 1px 0 rgba(255,255,255,0.03), 0 6px 18px rgba(0,0,0,0.25);
        }

        /* Alerts */
        div[data-testid="stAlert"] { border-radius: 12px; border: 1px solid var(--border); backdrop-filter: blur(4px); }

        /* Dataframe */
        div[data-testid="stDataFrame"] { border: 1px solid var(--border); border-radius: 14px; overflow: hidden; box-shadow: 0 8px 24px rgba(0,0,0,0.3); }

        /* Code / console */
        .stCode, pre {
            background: linear-gradient(180deg, #060a10 0%, #04070b 100%) !important;
            border: 1px solid var(--border) !important;
            border-radius: 14px !important;
            box-shadow: inset 0 0 24px rgba(0,0,0,0.5);
        }

        /* Custom header bar — glass appbar */
        .tb-appbar {
            display:flex; align-items:center; justify-content:space-between;
            padding: 16px 22px; margin-bottom: 16px;
            background: linear-gradient(90deg, rgba(18,26,39,0.92) 0%, rgba(24,34,47,0.92) 100%);
            border: 1px solid var(--border); border-radius: 18px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.04);
            backdrop-filter: blur(8px);
        }
        .tb-brand { display:flex; align-items:center; gap:14px; }
        .tb-logo {
            font-size: 1.5rem; width: 46px; height: 46px; display:flex; align-items:center; justify-content:center;
            border-radius: 12px; background: linear-gradient(135deg, rgba(0,208,156,0.18), rgba(47,155,255,0.18));
            border: 1px solid var(--border);
        }
        .tb-title { font-size: 1.18rem; font-weight: 800; color: var(--text); line-height:1.1; }
        .tb-sub { font-size: 0.75rem; color: var(--muted); }
        .tb-pill { padding: 7px 15px; border-radius: 999px; font-size: 0.78rem; font-weight: 700; border:1px solid var(--border); backdrop-filter: blur(4px); }
        .tb-pill.ok { color: var(--accent); background: rgba(0,208,156,0.12); border-color: rgba(0,208,156,0.4); box-shadow: 0 0 18px rgba(0,208,156,0.15); }
        .tb-pill.bad { color: var(--accent-red); background: rgba(246,70,93,0.12); border-color: rgba(246,70,93,0.4); }
        .tb-pill.warn { color: var(--accent-amber); background: rgba(240,185,11,0.12); border-color: rgba(240,185,11,0.4); }
        .tb-section {
            font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.14em; color: var(--muted);
            margin: 14px 0 6px; padding-bottom: 4px; border-bottom: 1px solid var(--border);
        }
        </style>
        """,
        unsafe_allow_html=True,
    )


_inject_theme()

# Single source of truth for the backend. On Streamlit Cloud set FOREXBOT_BACKEND_URL
# (or add it to .streamlit/secrets.toml) to your Render URL, e.g.
#   https://ai-forex-trading-bot.onrender.com
# The individual FOREXBOT_*_URL vars still override per-service if ever needed.
def _backend_base() -> str:
    base = os.getenv("FOREXBOT_BACKEND_URL")
    if not base:
        try:  # Streamlit secrets (works on Streamlit Community Cloud)
            base = st.secrets.get("FOREXBOT_BACKEND_URL")  # type: ignore[assignment]
        except Exception:
            base = None
    return (base or "http://localhost:8080").rstrip("/")


BACKEND_BASE_URL = _backend_base()

API_BASE_URL = os.getenv("FOREXBOT_API_URL", f"{BACKEND_BASE_URL}/api/bot")
AUTH_BASE_URL = os.getenv("FOREXBOT_AUTH_URL", f"{BACKEND_BASE_URL}/api/auth")
REQUEST_TIMEOUT = float(os.getenv("FOREXBOT_API_TIMEOUT", "10"))
LOGIN_TIMEOUT = float(os.getenv("FOREXBOT_LOGIN_TIMEOUT", "30"))
HTTP_RETRIES = int(os.getenv("FOREXBOT_HTTP_RETRIES", "2"))
RETRY_BACKOFF_SECONDS = float(os.getenv("FOREXBOT_RETRY_BACKOFF", "1.0"))


def _format_api_error(resp: requests.Response, fallback: str) -> str:
    """Convert backend error payloads into clean UI-friendly messages."""
    try:
        data = resp.json()
        if isinstance(data, dict):
            for key in ("error", "message", "detail", "status"):
                value = data.get(key)
                if value:
                    return str(value)
    except ValueError:
        pass

    # Avoid dumping full HTML/error pages into the UI.
    text = (resp.text or "").strip().replace("\n", " ")
    if text and len(text) < 180 and "<html" not in text.lower():
        return text
    return fallback


def _friendly_network_error(exc: Exception) -> str:
    """Human-readable network error text (no raw stack-like exception junk)."""
    if isinstance(exc, requests.Timeout):
        return (
            "Backend timed out. Render may be waking up or under load. "
            "Please retry in a few seconds."
        )
    if isinstance(exc, requests.ConnectionError):
        return "Could not connect to backend. Check URL/network and try again."
    return "Network error while contacting backend. Please retry."


def _request_with_retry(
    method: str,
    url: str,
    *,
    retries: int | None = None,
    timeout: float | None = None,
    retry_on_status: tuple[int, ...] = (408, 429, 500, 502, 503, 504),
    **kwargs: Any,
) -> requests.Response:
    """HTTP helper with small retry/backoff for transient backend failures."""
    attempts = (HTTP_RETRIES if retries is None else retries) + 1
    last_exc: Exception | None = None

    for attempt in range(attempts):
        try:
            resp = requests.request(method, url, timeout=timeout or REQUEST_TIMEOUT, **kwargs)
            if resp.status_code in retry_on_status and attempt < attempts - 1:
                time.sleep(RETRY_BACKOFF_SECONDS * (2 ** attempt))
                continue
            return resp
        except requests.RequestException as exc:
            last_exc = exc
            if attempt >= attempts - 1:
                raise
            time.sleep(RETRY_BACKOFF_SECONDS * (2 ** attempt))

    # Defensive fallback; the loop either returns or raises.
    if last_exc is not None:
        raise last_exc
    raise RuntimeError("Unexpected request retry state")


def _restore_auth_from_query_params() -> None:
    """Restore auth token from query params so browser refresh does not log out."""
    if st.session_state.get("auth_token"):
        return
    try:
        params = st.query_params
        token = params.get("token")
        user = params.get("user")
        expires = params.get("exp")

        token_val = token if isinstance(token, str) else (token[0] if token else None)
        user_val = user if isinstance(user, str) else (user[0] if user else None)
        exp_val = expires if isinstance(expires, str) else (expires[0] if expires else None)

        if token_val:
            st.session_state["auth_token"] = unquote(token_val)
            if user_val:
                st.session_state["auth_user"] = unquote(user_val)
            if exp_val:
                st.session_state["auth_expires"] = unquote(exp_val)
    except Exception:
        # Never crash UI over query-param parsing.
        pass


def _persist_auth_to_query_params() -> None:
    """Persist auth token in URL query params for refresh resilience."""
    token = st.session_state.get("auth_token")
    if not token:
        return
    desired = {
        "token": quote(str(token)),
        "user": quote(str(st.session_state.get("auth_user", ""))),
    }
    exp = st.session_state.get("auth_expires")
    if exp:
        desired["exp"] = quote(str(exp))

    current = dict(st.query_params)
    if any(str(current.get(k, "")) != v for k, v in desired.items()):
        st.query_params.clear()
        for k, v in desired.items():
            st.query_params[k] = v


def _clear_auth_query_params() -> None:
    """Remove auth-related query params on explicit logout."""
    try:
        params = dict(st.query_params)
        for key in ("token", "user", "exp"):
            params.pop(key, None)
        st.query_params.clear()
        for k, v in params.items():
            st.query_params[k] = v
    except Exception:
        pass


# --- Selectable option catalogs for the dashboard dropdowns. ---
TIMEFRAME_OPTIONS = ["M1", "M5", "M15", "M30", "H1", "H4", "D1"]
PAIR_OPTIONS = [
    "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD",
    "EURJPY", "GBPJPY", "XAUUSD", "XAGUSD", "BTCUSD", "ETHUSD",
]
TRADING_STYLE_OPTIONS = ["INTRADAY", "SCALPING", "SWING"]
# Supported AI providers and the models each one commonly exposes. The label is
# what the user sees; the value (provider id) is what the backend routes on.
AI_PROVIDERS = {
    "Gemini (Google)": "gemini",
    "ChatGPT (OpenAI)": "openai",
    "Claude (Anthropic)": "claude",
    "DeepSeek": "deepseek",
    "Grok (xAI)": "grok",
    "Mistral": "mistral",
}
AI_MODELS_BY_PROVIDER = {
    "gemini": [
        "gemini-3.6-flash", "gemini-2.0-flash", "gemini-2.0-flash-lite",
        "gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.5-flash", "gemini-2.5-pro",
    ],
    "openai": ["gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "o3-mini"],
    "claude": [
        "claude-3-5-sonnet-latest", "claude-3-5-haiku-latest", "claude-3-opus-latest",
    ],
    "deepseek": ["deepseek-chat", "deepseek-reasoner"],
    "grok": ["grok-2-latest", "grok-beta"],
    "mistral": ["mistral-large-latest", "mistral-small-latest"],
}


def _mt5_remembered() -> dict[str, str]:
    """Read remembered MT5 login/server from query params (Remember me)."""
    try:
        params = st.query_params

        def _val(key: str) -> str:
            raw = params.get(key)
            v = raw if isinstance(raw, str) else (raw[0] if raw else "")
            return unquote(v) if v else ""

        return {"login": _val("mt5_login"), "server": _val("mt5_server")}
    except Exception:
        return {"login": "", "server": ""}


def _remember_mt5(login: str, server: str) -> None:
    """Persist MT5 login/server in query params so a refresh keeps them."""
    try:
        st.query_params["mt5_login"] = quote(str(login))
        st.query_params["mt5_server"] = quote(str(server))
    except Exception:
        pass


def _forget_mt5() -> None:
    """Drop remembered MT5 login/server query params."""
    try:
        params = dict(st.query_params)
        for key in ("mt5_login", "mt5_server"):
            params.pop(key, None)
        st.query_params.clear()
        for k, v in params.items():
            st.query_params[k] = v
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Auth + backend API helpers
# ---------------------------------------------------------------------------
def _auth_headers(json_body: bool = False) -> dict[str, str]:
    """Standard headers, including the bearer token when authenticated."""
    headers: dict[str, str] = {}
    if json_body:
        headers["Content-Type"] = "application/json"
    token = st.session_state.get("auth_token")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def _clear_session_auth() -> None:
    """Wipe local auth/session state (used on explicit logout or hard 401)."""
    for key in (
        "auth_token", "auth_user", "auth_expires",
        "mt5_connected", "account_id", "last_status",
    ):
        st.session_state.pop(key, None)
    _clear_auth_query_params()
    _forget_mt5()


def _is_unauthorized(resp: requests.Response | None) -> bool:
    """Only an explicit 401 means the token is truly invalid/expired.

    Transient errors (timeouts, 5xx, network) must NOT log the user out —
    that was the cause of the random 'auto logout' behaviour.
    """
    return resp is not None and resp.status_code == 401


def post_login(username: str, password: str) -> requests.Response:
    # Use the longer login timeout so a cold-starting Render backend does not
    # trip a 10s read timeout during authentication.
    return _request_with_retry(
        "POST",
        f"{AUTH_BASE_URL}/login",
        json={"username": username, "password": password},
        headers={"Content-Type": "application/json"},
        timeout=LOGIN_TIMEOUT,
    )


def logout() -> None:
    """Best-effort server-side revoke, then clear local session + query params."""
    token = st.session_state.get("auth_token")
    if token:
        try:
            _request_with_retry(
                "POST", f"{AUTH_BASE_URL}/logout",
                headers=_auth_headers(), retries=0, timeout=REQUEST_TIMEOUT,
            )
        except requests.RequestException:
            pass  # Never block logout on a network error.
    _clear_session_auth()


def render_login() -> None:
    """Login gate. Persists the token to query params so refresh stays signed in."""
    _, mid, _ = st.columns([1, 1.15, 1])
    with mid:
        st.markdown(
            """
            <div class="tb-appbar" style="flex-direction:column;text-align:center;gap:6px;padding:26px 20px;">
                <div class="tb-logo" style="font-size:2.4rem;">📈</div>
                <div class="tb-title" style="font-size:1.4rem;">AI Forex Trading Bot</div>
                <div class="tb-sub">Autonomous trading desk · Risk-guarded execution</div>
            </div>
            """,
            unsafe_allow_html=True,
        )
        with st.form("login_form"):
            st.markdown('<div class="tb-section">Account Sign-in</div>', unsafe_allow_html=True)
            username = st.text_input("Username", value="", placeholder="Enter username")
            password = st.text_input("Password", value="", type="password", placeholder="Enter password")
            submit = st.form_submit_button("Log in", use_container_width=True)

        if not submit:
            return

        if not username.strip() or not password:
            st.error("Username and password are required.")
            return

        with st.spinner("Signing in… (backend may be waking up)"):
            try:
                resp = post_login(username.strip(), password)
            except requests.RequestException as exc:
                st.error(_friendly_network_error(exc))
                return

        if resp.ok:
            try:
                data = resp.json()
            except ValueError:
                data = {}
            token = data.get("token")
            if not token:
                st.error("Login succeeded but no token was returned by the backend.")
                return
            st.session_state["auth_token"] = token
            st.session_state["auth_user"] = data.get("username") or username.strip()
            if data.get("expiresAt"):
                st.session_state["auth_expires"] = str(data.get("expiresAt"))
            _persist_auth_to_query_params()
            st.rerun()
        elif resp.status_code == 401:
            st.error("Login failed: invalid username or password.")
        else:
            st.error(f"Login failed: {_format_api_error(resp, f'HTTP {resp.status_code}')}")


def get_health() -> tuple[bool, str]:
    """Ping the backend health endpoint; returns (healthy, message)."""
    try:
        resp = _request_with_retry("GET", f"{API_BASE_URL}/health", headers=_auth_headers())
    except requests.RequestException as exc:
        return False, _friendly_network_error(exc)
    if resp.ok:
        try:
            data = resp.json()
            if isinstance(data, dict):
                return True, str(data.get("status", "ok"))
        except ValueError:
            pass
        return True, "ok"
    return False, _format_api_error(resp, f"HTTP {resp.status_code}")


def post_mt5_connect(login: int, password: str, server: str) -> requests.Response:
    return _request_with_retry(
        "POST",
        f"{BACKEND_BASE_URL}/api/mt5/connect",
        json={"login": login, "password": password, "server": server},
        headers=_auth_headers(json_body=True),
    )


def get_mt5_status() -> dict[str, Any] | None:
    """Fetch server-side MT5 credential/bridge status (survives UI refresh)."""
    try:
        resp = _request_with_retry(
            "GET", f"{BACKEND_BASE_URL}/api/mt5/status", headers=_auth_headers()
        )
    except requests.RequestException:
        return None
    if resp.ok:
        try:
            data = resp.json()
            return data if isinstance(data, dict) else None
        except ValueError:
            return None
    return None


def _restore_mt5_state() -> None:
    """Re-hydrate MT5 connection state from the backend so a browser refresh
    does not make the account look disconnected (credentials live server-side)."""
    status = get_mt5_status()
    if not status:
        return
    if status.get("configured"):
        st.session_state["mt5_connected"] = True
        if status.get("login") is not None:
            st.session_state["account_id"] = str(status.get("login"))


def post_guardrails(payload: dict[str, Any]) -> requests.Response:
    return _request_with_retry(
        "POST",
        f"{BACKEND_BASE_URL}/api/risk/guardrails",
        json=payload,
        headers=_auth_headers(json_body=True),
    )


def get_ai_settings() -> dict[str, Any] | None:
    """Fetch current AI + strategy settings (API key is never returned)."""
    try:
        resp = _request_with_retry(
            "GET", f"{BACKEND_BASE_URL}/api/ai/settings", headers=_auth_headers()
        )
    except requests.RequestException:
        return None
    if resp.ok:
        try:
            data = resp.json()
            return data if isinstance(data, dict) else None
        except ValueError:
            return None
    return None


def post_ai_settings(payload: dict[str, Any]) -> requests.Response:
    return _request_with_retry(
        "POST",
        f"{BACKEND_BASE_URL}/api/ai/settings",
        json=payload,
        headers=_auth_headers(json_body=True),
    )


def get_strategy_settings() -> dict[str, Any] | None:
    """Fetch strategy mode (RULES / AI) and rule-based parameters."""
    try:
        resp = _request_with_retry(
            "GET", f"{BACKEND_BASE_URL}/api/strategy/settings", headers=_auth_headers()
        )
    except requests.RequestException:
        return None
    if resp.ok:
        try:
            data = resp.json()
            return data if isinstance(data, dict) else None
        except ValueError:
            return None
    return None


def post_strategy_settings(payload: dict[str, Any]) -> requests.Response:
    return _request_with_retry(
        "POST",
        f"{BACKEND_BASE_URL}/api/strategy/settings",
        json=payload,
        headers=_auth_headers(json_body=True),
    )


def post_start(account_id: str | None) -> requests.Response:
    params = {"accountId": account_id} if account_id else None
    return _request_with_retry(
        "POST", f"{API_BASE_URL}/start", params=params, headers=_auth_headers()
    )


def post_stop() -> requests.Response:
    return _request_with_retry("POST", f"{API_BASE_URL}/stop", headers=_auth_headers())


def post_run_cycle() -> requests.Response:
    return _request_with_retry("POST", f"{API_BASE_URL}/run-cycle", headers=_auth_headers())


def get_status() -> requests.Response:
    return _request_with_retry("GET", f"{API_BASE_URL}/status", headers=_auth_headers())


def get_telemetry() -> dict[str, Any] | None:
    try:
        resp = _request_with_retry(
            "GET", f"{BACKEND_BASE_URL}/api/monitor/telemetry", headers=_auth_headers()
        )
    except requests.RequestException:
        return None
    if resp.ok:
        try:
            data = resp.json()
            return data if isinstance(data, dict) else None
        except ValueError:
            return None
    return None


def get_positions() -> list[dict[str, Any]]:
    try:
        resp = _request_with_retry(
            "GET", f"{BACKEND_BASE_URL}/api/monitor/positions", headers=_auth_headers()
        )
    except requests.RequestException:
        return []
    if resp.ok:
        try:
            data = resp.json()
        except ValueError:
            return []
        if isinstance(data, list):
            return data
        if isinstance(data, dict):
            return data.get("positions", []) or []
    return []


def get_activity(limit: int = 40) -> list[dict[str, Any]]:
    try:
        resp = _request_with_retry(
            "GET",
            f"{BACKEND_BASE_URL}/api/monitor/activity",
            params={"limit": limit},
            headers=_auth_headers(),
        )
    except requests.RequestException:
        return []
    if resp.ok:
        try:
            data = resp.json()
        except ValueError:
            return []
        if isinstance(data, list):
            return data
        if isinstance(data, dict):
            return data.get("activity", []) or []
    return []


def clear_activity() -> bool:
    """Delete the AI activity / decision feed on the backend."""
    try:
        resp = _request_with_retry(
            "DELETE",
            f"{BACKEND_BASE_URL}/api/monitor/activity",
            headers=_auth_headers(),
            retries=0,
        )
    except requests.RequestException:
        return False
    return resp.ok


# Restore token before rendering auth gate.
_restore_auth_from_query_params()

# --- Authentication gate: nothing below renders until logged in. ---
if not st.session_state.get("auth_token"):
    render_login()
    st.stop()

# Keep query params synchronized once authenticated.
_persist_auth_to_query_params()

# Re-hydrate MT5 connection state from the backend (survives browser refresh).
if not st.session_state.get("_mt5_state_restored"):
    _restore_mt5_state()
    st.session_state["_mt5_state_restored"] = True
    # Also seed the bot running state so the banner is correct after refresh.
    try:
        _sresp = get_status()
        if _sresp.ok:
            st.session_state["last_status"] = _sresp.json()
    except requests.RequestException:
        pass

healthy, health_msg = get_health()
_status_cls = "ok" if healthy else "bad"
_status_txt = f"● Backend Online · {health_msg}" if healthy else f"● Backend Offline · {health_msg}"

st.markdown(
    f"""
    <div class="tb-appbar">
        <div class="tb-brand">
            <div class="tb-logo">📈</div>
            <div>
                <div class="tb-title">AI Forex Trading Bot</div>
                <div class="tb-sub">Autonomous trading desk · Signed in as {st.session_state.get('auth_user', 'user')}</div>
            </div>
        </div>
        <div class="tb-pill {_status_cls}">{_status_txt}</div>
    </div>
    """,
    unsafe_allow_html=True,
)

with st.sidebar:
    st.markdown('<div class="tb-section">Session</div>', unsafe_allow_html=True)
    st.caption(f"Signed in as **{st.session_state.get('auth_user', 'user')}**")
    if st.session_state.get("auth_expires"):
        st.caption(f"Session expires: {st.session_state['auth_expires']}")
    if st.button("Log out", use_container_width=True):
        logout()
        st.rerun()


with st.sidebar:
    st.markdown('<div class="tb-section">Step 1 · MT5 Broker Connection</div>', unsafe_allow_html=True)
    _remembered = _mt5_remembered()
    # Seed fields once from remembered values; afterwards the widget keys keep
    # them across refreshes/reruns (fixes the "Refresh wipes my inputs" issue).
    if "mt5_login" not in st.session_state and _remembered.get("login"):
        st.session_state["mt5_login"] = _remembered.get("login", "")
    if "mt5_server" not in st.session_state and _remembered.get("server"):
        st.session_state["mt5_server"] = _remembered.get("server", "")
    _remember_default = bool(_remembered.get("login") or _remembered.get("server"))
    with st.form("mt5_form"):
        mt5_login = st.text_input("Account Number", key="mt5_login", placeholder="e.g. 51234567")
        mt5_password = st.text_input("Password", key="mt5_password", type="password")
        mt5_server = st.text_input("Server Name", key="mt5_server", placeholder="e.g. The5ers-Live")
        mt5_remember = st.checkbox(
            "Remember me", value=_remember_default, key="mt5_remember",
            help="Keep account number & server filled after a refresh until you log out.",
        )
        mt5_submit = st.form_submit_button("Connect / Save Credentials", use_container_width=True)

    if mt5_submit:
        if not mt5_login.strip().isdigit():
            st.error("Account Number must be numeric")
        elif not mt5_password or not mt5_server.strip():
            st.error("Password and Server Name are required")
        else:
            try:
                resp = post_mt5_connect(int(mt5_login), mt5_password, mt5_server.strip())
                if resp.ok:
                    st.success(f"MT5 credentials saved for {mt5_server.strip()}")
                    st.session_state["mt5_connected"] = True
                    st.session_state["account_id"] = mt5_login.strip()
                    # Persist (or clear) the non-secret fields per the Remember-me choice.
                    if mt5_remember:
                        _remember_mt5(mt5_login.strip(), mt5_server.strip())
                    else:
                        _forget_mt5()
                    st.rerun()
                else:
                    msg = _format_api_error(resp, f"HTTP {resp.status_code}")
                    st.error(f"Connect failed: {msg}")
            except requests.RequestException as exc:
                st.error(_friendly_network_error(exc))

    if st.session_state.get("mt5_connected"):
        st.caption("MT5 credentials configured ✅")
    else:
        st.caption("Fill MT5 credentials to unlock the next steps.")

# Progressive-enable gating flags.
_mt5_ready = bool(st.session_state.get("mt5_connected"))
_ai_ready = bool(
    st.session_state.get("ai_saved")
    or (st.session_state.get("_ai_settings") or {}).get("apiKeySet")
    or str((st.session_state.get("_strategy") or {}).get("mode", "")).upper() == "RULES"
)
_guardrails_ready = bool(st.session_state.get("guardrails_saved"))

with st.sidebar:
    st.markdown('<div class="tb-section">Step 2 · AI Model & Strategy</div>', unsafe_allow_html=True)
    if not _mt5_ready:
        st.caption("🔒 Locked — connect MT5 first.")
    else:
        st.caption("Pick the model, supply its API key, and set timeframe / trading style.")

    # Load current settings once so the controls reflect the backend state.
    if "_ai_settings" not in st.session_state:
        st.session_state["_ai_settings"] = get_ai_settings() or {}
    _ai = st.session_state["_ai_settings"]

    def _idx(options: list[str], value: str | None, default: int = 0) -> int:
        try:
            return options.index(value) if value in options else default
        except Exception:
            return default

    # --- Strategy type selector: Rule-based (no API key) vs AI-based -------
    if "_strategy" not in st.session_state:
        st.session_state["_strategy"] = get_strategy_settings() or {"mode": "RULES"}
    _strat = st.session_state["_strategy"]
    _mode_labels = {"Rule Based (no API key)": "RULES", "AI Based (LLM)": "AI"}
    _mode_default = 1 if str(_strat.get("mode", "RULES")).upper() == "AI" else 0
    _mode_label = st.radio(
        "Strategy Type", list(_mode_labels.keys()), index=_mode_default,
        key="strategy_mode", disabled=not _mt5_ready,
        help="Rule Based uses EMA/RSI/ATR indicators — no API key, no AI errors. "
             "AI Based sends candles to the selected LLM provider.",
    )
    strategy_mode = _mode_labels[_mode_label]

    if strategy_mode == "AI":
        # Provider select lives OUTSIDE the form so changing it immediately
        # refreshes the model dropdown for that provider.
        _provider_labels = list(AI_PROVIDERS.keys())
        _current_provider_id = _ai.get("provider", "gemini")
        _current_label = next(
            (lbl for lbl, pid in AI_PROVIDERS.items() if pid == _current_provider_id),
            _provider_labels[0],
        )
        ai_provider_label = st.selectbox(
            "AI Provider", _provider_labels, index=_idx(_provider_labels, _current_label, 0),
            key="ai_provider", disabled=not _mt5_ready,
        )
        ai_provider = AI_PROVIDERS[ai_provider_label]
        _model_options = AI_MODELS_BY_PROVIDER.get(ai_provider, [])

        # Number of API keys to configure. Multiple free-tier keys let the backend
        # automatically fail over to the next key when the active one is
        # rate-limited (HTTP 429 / quota) instead of pausing for 60s.
        _key_count_default = int(_ai.get("apiKeyCount") or 1) or 1
        _key_count_default = min(max(_key_count_default, 1), 5)
        ai_key_count = st.selectbox(
            "Number of API Keys", [1, 2, 3, 4, 5],
            index=_key_count_default - 1,
            key="ai_key_count", disabled=not _mt5_ready,
            help="Add multiple free-tier keys; the bot rotates to the next one on a 429 rate-limit.",
        )

        with st.form("ai_settings_form"):
            ai_model = st.selectbox(
                "AI Model", _model_options,
                index=_idx(_model_options, _ai.get("model"), 0),
                key="ai_model", disabled=not _mt5_ready,
            )
            _keys_set = int(_ai.get("apiKeyCount") or 0)
            ai_api_keys: list[str] = []
            for _i in range(int(ai_key_count)):
                _already = _i < _keys_set
                ai_api_keys.append(
                    st.text_input(
                        f"API Key #{_i + 1}", key=f"ai_api_key_{_i}", type="password",
                        placeholder=("•••• already set" if _already else f"Paste {ai_provider_label} API key #{_i + 1}"),
                        help="Stored in backend memory only. Leave all blank to keep existing keys.",
                        disabled=not _mt5_ready,
                    )
                )
            ai_timeframe = st.selectbox(
                "Timeframe", TIMEFRAME_OPTIONS,
                index=_idx(TIMEFRAME_OPTIONS, _ai.get("timeframe"), 2),
                key="ai_timeframe", disabled=not _mt5_ready,
            )
            ai_style = st.selectbox(
                "Trading Type", TRADING_STYLE_OPTIONS,
                index=_idx(TRADING_STYLE_OPTIONS, _ai.get("tradingStyle"), 0),
                key="ai_style", disabled=not _mt5_ready,
            )
            ai_submit = st.form_submit_button(
                "Save AI Settings", use_container_width=True, disabled=not _mt5_ready
            )

        if ai_submit:
            ai_payload = {
                "provider": ai_provider,
                "model": ai_model,
                "timeframe": ai_timeframe,
                "tradingStyle": ai_style,
            }
            _clean_keys = [k.strip() for k in ai_api_keys if k and k.strip()]
            if _clean_keys:
                ai_payload["apiKeys"] = _clean_keys
            try:
                resp = post_ai_settings(ai_payload)
                if resp.ok:
                    post_strategy_settings({"mode": "AI"})
                    st.session_state["_strategy"] = get_strategy_settings() or {"mode": "AI"}
                    st.session_state["_ai_settings"] = resp.json() if resp.content else ai_payload
                    st.session_state["ai_saved"] = True
                    _keys_label = f" · {len(_clean_keys)} key(s)" if _clean_keys else ""
                    st.success(f"AI settings saved · {ai_provider_label} · {ai_model} · {ai_timeframe} · {ai_style}{_keys_label}")
                    st.rerun()
                else:
                    msg = _format_api_error(resp, f"HTTP {resp.status_code}")
                    st.error(f"AI settings rejected: {msg}")
            except requests.RequestException as exc:
                st.error(_friendly_network_error(exc))

        if _ai.get("apiKeySet") or st.session_state.get("ai_saved"):
            _count = int(_ai.get("apiKeyCount") or 0)
            if _count > 1:
                st.caption(f"API keys configured ✅ ({_count} keys · auto fail-over on rate-limit)")
            else:
                st.caption("API key configured ✅")
    else:
        st.caption("Rule Based strategy: EMA trend + pullback entry, RSI filter, ATR stop-loss.")
        with st.form("rules_settings_form"):
            r_timeframe = st.selectbox(
                "Timeframe", TIMEFRAME_OPTIONS,
                index=_idx(TIMEFRAME_OPTIONS, _ai.get("timeframe"), 2),
                key="rules_timeframe", disabled=not _mt5_ready,
            )
            _c1, _c2, _c3 = st.columns(3)
            r_ema_fast = _c1.number_input("EMA Fast", 2, 200, int(_strat.get("emaFast") or 20), key="r_ema_fast")
            r_ema_slow = _c2.number_input("EMA Slow", 3, 400, int(_strat.get("emaSlow") or 50), key="r_ema_slow")
            r_ema_trend = _c3.number_input("EMA Trend", 10, 500, int(_strat.get("emaTrend") or 200), key="r_ema_trend")
            _c4, _c5 = st.columns(2)
            r_rsi_period = _c4.number_input("RSI Period", 2, 50, int(_strat.get("rsiPeriod") or 14), key="r_rsi_p")
            r_atr_period = _c5.number_input("ATR Period", 2, 50, int(_strat.get("atrPeriod") or 14), key="r_atr_p")
            _c6, _c7 = st.columns(2)
            r_buy_min = _c6.number_input("RSI Buy Min", 0.0, 100.0, float(_strat.get("rsiBuyMin") or 40.0), key="r_bmin")
            r_buy_max = _c7.number_input("RSI Buy Max", 0.0, 100.0, float(_strat.get("rsiBuyMax") or 70.0), key="r_bmax")
            _c8, _c9 = st.columns(2)
            r_sell_min = _c8.number_input("RSI Sell Min", 0.0, 100.0, float(_strat.get("rsiSellMin") or 30.0), key="r_smin")
            r_sell_max = _c9.number_input("RSI Sell Max", 0.0, 100.0, float(_strat.get("rsiSellMax") or 60.0), key="r_smax")
            _c10, _c11 = st.columns(2)
            r_atr_mult = _c10.number_input("SL = ATR ×", 0.1, 10.0, float(_strat.get("atrSlMultiplier") or 1.5), 0.1, key="r_atrm")
            r_rr = _c11.number_input("Reward : Risk", 0.1, 10.0, float(_strat.get("rewardRisk") or 2.0), 0.1, key="r_rr")
            r_min_conf = st.number_input(
                "Min rules that must agree (of 32)", 1, 10, int(_strat.get("minConfluence") or 2),
                key="r_min_conf",
                help="1 = most trades (lower quality); 2-3 = balanced; 4+ = few, stronger trades.",
            )
            rules_submit = st.form_submit_button(
                "Save Rule Settings", use_container_width=True, disabled=not _mt5_ready
            )

        if rules_submit:
            rules_payload = {
                "mode": "RULES",
                "emaFast": int(r_ema_fast), "emaSlow": int(r_ema_slow), "emaTrend": int(r_ema_trend),
                "rsiPeriod": int(r_rsi_period), "atrPeriod": int(r_atr_period),
                "rsiBuyMin": r_buy_min, "rsiBuyMax": r_buy_max,
                "rsiSellMin": r_sell_min, "rsiSellMax": r_sell_max,
                "atrSlMultiplier": r_atr_mult, "rewardRisk": r_rr,
                "minConfluence": int(r_min_conf),
            }
            try:
                resp = post_strategy_settings(rules_payload)
                if resp.ok:
                    # Timeframe drives candle pacing; keep current provider/model untouched.
                    post_ai_settings({
                        "provider": _ai.get("provider", "gemini"),
                        "model": _ai.get("model"),
                        "timeframe": r_timeframe,
                        "tradingStyle": _ai.get("tradingStyle"),
                    })
                    st.session_state["_strategy"] = resp.json()
                    st.session_state["_ai_settings"] = get_ai_settings() or _ai
                    st.session_state["ai_saved"] = True
                    st.success(f"Rule strategy saved · {r_timeframe} · EMA {r_ema_fast}/{r_ema_slow}/{r_ema_trend}")
                    st.rerun()
                else:
                    st.error(f"Rule settings rejected: {_format_api_error(resp, f'HTTP {resp.status_code}')}")
            except requests.RequestException as exc:
                st.error(_friendly_network_error(exc))

        if str(_strat.get("mode", "")).upper() == "RULES" and st.session_state.get("ai_saved"):
            st.caption("Rule Based strategy active ✅ (no API key needed)")


with st.sidebar:
    st.markdown('<div class="tb-section">Step 3 · AI Risk Guardrails</div>', unsafe_allow_html=True)
    if not _ai_ready:
        st.caption("🔒 Locked — save AI settings first.")
    else:
        st.caption("Define boundaries; the AI agent trades autonomously within them.")
    _gr_disabled = not _ai_ready
    with st.form("guardrails_form"):
        autonomous_enabled = st.checkbox(
            "Enable Autonomous AI Trading", value=True, key="gr_autonomous",
            disabled=_gr_disabled,
        )
        max_risk_percent = st.number_input(
            "Max Risk Per Trade (%)", min_value=0.1, max_value=100.0, value=1.0, step=0.1,
            key="gr_risk", disabled=_gr_disabled,
        )
        allowed_symbols = st.multiselect(
            "Trading Pairs", PAIR_OPTIONS, default=["EURUSD", "XAUUSD"],
            key="gr_symbols", disabled=_gr_disabled,
        )
        lot_size = st.number_input(
            "Lot Size (0 = auto risk-based)",
            min_value=0.0, max_value=100.0, value=0.10, step=0.01, key="gr_lot",
            help="Fixed order size in lots. Set 0 to size automatically from Max Risk % and Stop Loss.",
            disabled=_gr_disabled,
        )
        max_drawdown_usd = st.number_input(
            "Max Drawdown (USD)", min_value=1.0, value=500.0, step=10.0,
            key="gr_drawdown", disabled=_gr_disabled,
        )
        gr_stop_loss_pips = st.number_input(
            "Stop Loss (pips)", min_value=1, max_value=1000, value=20, step=1, key="gr_sl",
            disabled=_gr_disabled,
        )
        gr_take_profit_pips = st.number_input(
            "Take Profit (pips)", min_value=1, max_value=2000, value=40, step=1, key="gr_tp",
            disabled=_gr_disabled,
        )
        account_balance_usd = st.number_input(
            "Account Balance (USD)", min_value=1.0, value=10000.0, step=100.0,
            key="gr_balance", disabled=_gr_disabled,
        )
        guardrails_submit = st.form_submit_button(
            "Save Risk Guardrails", use_container_width=True, disabled=_gr_disabled
        )

    if guardrails_submit:
        symbols = [s.strip().upper() for s in allowed_symbols if s.strip()]
        guardrails_payload = {
            "maxRiskPercent": float(max_risk_percent),
            "allowedSymbols": symbols,
            "maxDrawdownUsd": float(max_drawdown_usd),
            "stopLossPips": int(gr_stop_loss_pips),
            "takeProfitPips": int(gr_take_profit_pips),
            "accountBalanceUsd": float(account_balance_usd),
            "autonomousEnabled": bool(autonomous_enabled),
            "lotSize": float(lot_size),
        }
        try:
            resp = post_guardrails(guardrails_payload)
            if resp.ok:
                mode = "AUTONOMOUS" if autonomous_enabled else "manual"
                st.session_state["guardrails_saved"] = True
                st.success(f"Guardrails saved ({mode} mode)")
                st.rerun()
            else:
                msg = _format_api_error(resp, f"HTTP {resp.status_code}")
                st.error(f"Guardrails rejected: {msg}")
        except requests.RequestException as exc:
            st.error(_friendly_network_error(exc))

    if st.session_state.get("guardrails_saved"):
        st.caption("Guardrails saved ✅ — you can Start the AI now.")

st.subheader("Step 4 · Autonomous Engine")

if not _guardrails_ready:
    st.info("🔒 Complete Steps 1–3 (MT5 → AI settings → Guardrails) to enable the engine.")

# Live bot state banner so the operator can see Start/Stop actually took effect.
_bot_status = st.session_state.get("last_status") or {}
_running = bool(_bot_status.get("running"))
_state_cls = "ok" if _running else "bad"
_state_txt = "● RUNNING" if _running else "● STOPPED"
st.markdown(
    f'<div class="tb-pill {_state_cls}" style="display:inline-block;margin-bottom:10px;">'
    f'AI Engine: {_state_txt}</div>',
    unsafe_allow_html=True,
)

start_col, cycle_col, stop_col, refresh_col = st.columns(4)

if start_col.button("Start AI", use_container_width=True, type="primary",
                    disabled=not _guardrails_ready):
    try:
        resp = post_start(st.session_state.get("account_id"))
        if resp.ok:
            st.session_state["last_status"] = resp.json()
            # Kick one cycle immediately so the console visibly reacts.
            try:
                cyc = post_run_cycle()
                if cyc.ok:
                    results = cyc.json().get("results", [])
                    st.success("AI engine started · " + (", ".join(results) if results else "cycle triggered"))
                else:
                    st.success("AI engine started")
            except requests.RequestException:
                st.success("AI engine started")
            st.rerun()
        else:
            msg = _format_api_error(resp, f"HTTP {resp.status_code}")
            st.error(f"Start failed: {msg}")
    except requests.RequestException as exc:
        st.error(_friendly_network_error(exc))

if cycle_col.button("Run Cycle Now", use_container_width=True,
                    disabled=not _guardrails_ready):
    try:
        resp = post_run_cycle()
        if resp.ok:
            results = resp.json().get("results", [])
            st.success("Cycle ran · " + (", ".join(results) if results else "see console"))
            st.rerun()
        else:
            msg = _format_api_error(resp, f"HTTP {resp.status_code}")
            st.error(f"Run cycle failed: {msg}")
    except requests.RequestException as exc:
        st.error(_friendly_network_error(exc))

if stop_col.button("Stop AI", use_container_width=True):
    try:
        resp = post_stop()
        if resp.ok:
            st.session_state["last_status"] = resp.json()
            st.success("AI engine stopped")
            st.rerun()
        else:
            msg = _format_api_error(resp, f"HTTP {resp.status_code}")
            st.error(f"Stop failed: {msg}")
    except requests.RequestException as exc:
        st.error(_friendly_network_error(exc))

if refresh_col.button("Refresh", use_container_width=True):
    try:
        resp = get_status()
        if resp.ok:
            st.session_state["last_status"] = resp.json()
            st.rerun()
        else:
            msg = _format_api_error(resp, f"HTTP {resp.status_code}")
            st.error(f"Status failed: {msg}")
    except requests.RequestException as exc:
        st.error(_friendly_network_error(exc))

st.divider()
st.subheader("Live Telemetry")

telemetry = get_telemetry()
telemetry = telemetry or {}
bridge_online = bool(telemetry.get("bridgeOnline"))
mt5_connected = bool(telemetry.get("mt5Connected"))
account = telemetry.get("account") or {}
positions = get_positions()
open_pnl = sum(float(p.get("profit", 0.0)) for p in positions)

# The relay (mt5_bridge.py) can be online while the MT5 terminal/account is not
# yet logged in — surface both so the status is unambiguous.
if bridge_online and mt5_connected:
    st.success("🟢 Bridge Connected · MT5 account live")
elif bridge_online:
    st.warning("🟡 Bridge relay online · MT5 account NOT connected "
               "(enter broker credentials / start MT5 terminal)")
else:
    st.error("🔴 Bridge Disconnected (mt5_bridge.py is not connected)")

t1, t2, t3 = st.columns(3)
t1.metric("Balance", f"{float(account.get('balance', 0.0)):,.2f}")
t2.metric("Equity", f"{float(account.get('equity', 0.0)):,.2f}")
t3.metric("Open P&L", f"{open_pnl:,.2f}")

if positions:
    st.markdown("**Open Positions**")
    st.dataframe(
        [
            {
                "Symbol": p.get("symbol"),
                "Type": p.get("type"),
                "Volume": p.get("volume"),
                "Open": p.get("priceOpen"),
                "Current": p.get("priceCurrent"),
                "P&L": p.get("profit"),
            }
            for p in positions
        ],
        use_container_width=True,
        hide_index=True,
    )

st.divider()
_console_hdr, _console_del = st.columns([0.85, 0.15])
with _console_hdr:
    st.subheader("AI Activity Console")
    st.caption("Live stream of the LLM's market analysis, reasoning and BUY / SELL / HOLD decisions.")
with _console_del:
    st.write("")
    if st.button("🗑 Clear", use_container_width=True, help="Delete all activity / decision logs"):
        if clear_activity():
            st.success("Activity log cleared")
            st.rerun()
        else:
            st.error("Failed to clear activity log")

_DECISION_ICON = {"BUY": "🟢", "SELL": "🔴", "HOLD": "⚪", "REJECTED": "⛔"}


def _classify_decision(action: str, message: str) -> tuple[str, str]:
    """Split an activity message into (status, rule/reason) so the operator can
    see WHY the AI passed or blocked a trade."""
    msg = message or ""
    action = (action or "").upper()

    # Executed / attempted order lines look like:
    #   "BUY 0.50 lots @ 4293 -> SEND_FAILED (AI window decision (conf 0.75))"
    if "->" in msg:
        left, _, right = msg.partition("->")
        status_part = right.strip()
        # The rule/rationale is the trailing "( ... )" segment.
        rule = status_part
        if "(" in status_part:
            status_code = status_part[: status_part.find("(")].strip()
            rule = status_part[status_part.find("(") + 1 : status_part.rfind(")")].strip()
        else:
            status_code = status_part
        passed = any(k in status_code.upper() for k in ("DONE", "PLACED", "ACCEPT", "FILLED", "OK"))
        status = ("✅ EXECUTED · " if passed else "❌ FAILED · ") + status_code
        return status, rule or left.strip()

    # Rule-based blocks / holds.
    if action == "REJECTED":
        return "⛔ BLOCKED", msg
    if action == "HOLD":
        return "⏸ HOLD", msg
    if action in ("BUY", "SELL"):
        return "🟢 SIGNAL", msg
    return action or "•", msg


activity = get_activity(limit=40)
if activity:
    # 1) Human-friendly decision table — WHICH RULE drove each pass/fail.
    st.markdown("**Decision rules — why each trade passed or was blocked**")
    rows = []
    for entry in activity:
        ts = (entry.get("timestamp") or "")[:19].replace("T", " ")
        action = str(entry.get("action", "")).upper()
        status, rule = _classify_decision(action, entry.get("message", ""))
        rows.append(
            {
                "Time": ts,
                "Symbol": entry.get("symbol", ""),
                "Decision": f"{_DECISION_ICON.get(action, '•')} {action}",
                "Result": status,
                "Rule / Reason": rule,
            }
        )
    st.dataframe(rows, use_container_width=True, hide_index=True)

    # 2) Raw stream (kept for detail / copy-paste).
    with st.expander("Raw activity log", expanded=False):
        lines = []
        for entry in activity:
            ts = (entry.get("timestamp") or "")[:19].replace("T", " ")
            action = str(entry.get("action", "")).upper()
            icon = _DECISION_ICON.get(action, "•")
            lines.append(
                f"{icon} [{ts}] {entry.get('symbol', '')} {action}: {entry.get('message', '')}"
            )
        st.code("\n".join(lines), language="text")
else:
    st.info("Waiting for the AI engine to stream market analysis...")

auto_refresh = st.checkbox("Auto-refresh console (5s)", value=False)
if auto_refresh:
    time.sleep(5)
    st.rerun()


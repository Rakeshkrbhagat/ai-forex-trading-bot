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
            --accent-red: #f6465d;
            --accent-amber: #f0b90b;
            --bg: #0b0e11;
            --panel: #151a21;
            --panel-2: #1c232c;
            --border: #262d38;
            --muted: #8b95a5;
            --text: #e6e9ef;
        }
        .stApp { background: radial-gradient(1200px 600px at 20% -10%, #12181f 0%, var(--bg) 55%); }
        #MainMenu, footer, header {visibility: hidden;}
        .block-container { padding-top: 1.2rem; padding-bottom: 3rem; max-width: 1280px; }

        /* Headings */
        h1, h2, h3, h4 { color: var(--text); font-weight: 700; letter-spacing: -0.01em; }
        .stCaption, .st-emotion-cache-1 .stMarkdown p { color: var(--muted); }

        /* Cards / panels */
        div[data-testid="stMetric"] {
            background: linear-gradient(180deg, var(--panel-2) 0%, var(--panel) 100%);
            border: 1px solid var(--border);
            border-radius: 14px;
            padding: 16px 18px;
            box-shadow: 0 4px 18px rgba(0,0,0,0.35);
        }
        div[data-testid="stMetricLabel"] p { color: var(--muted); font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.08em; }
        div[data-testid="stMetricValue"] { color: var(--text); font-weight: 700; }

        /* Buttons */
        .stButton > button, .stFormSubmitButton > button {
            border-radius: 10px;
            border: 1px solid var(--border);
            background: var(--panel-2);
            color: var(--text);
            font-weight: 600;
            transition: all .15s ease;
        }
        .stButton > button:hover, .stFormSubmitButton > button:hover {
            border-color: var(--accent);
            color: var(--accent);
            box-shadow: 0 0 0 2px rgba(0,208,156,0.12);
        }
        .stFormSubmitButton > button {
            background: linear-gradient(90deg, var(--accent) 0%, #00b487 100%);
            color: #04120d; border: none;
        }
        .stFormSubmitButton > button:hover { color: #04120d; filter: brightness(1.05); }

        /* Inputs */
        .stTextInput input, .stNumberInput input, .stTextArea textarea {
            background: var(--panel) !important;
            border: 1px solid var(--border) !important;
            border-radius: 10px !important;
            color: var(--text) !important;
        }
        .stTextInput input:focus, .stNumberInput input:focus { border-color: var(--accent) !important; }

        /* Sidebar */
        section[data-testid="stSidebar"] {
            background: #0d1116;
            border-right: 1px solid var(--border);
        }
        section[data-testid="stSidebar"] .stForm { border: 1px solid var(--border); border-radius: 12px; padding: 6px 10px; background: var(--panel); }

        /* Alerts */
        div[data-testid="stAlert"] { border-radius: 12px; border: 1px solid var(--border); }

        /* Dataframe */
        div[data-testid="stDataFrame"] { border: 1px solid var(--border); border-radius: 12px; overflow: hidden; }

        /* Code / console */
        .stCode, pre { background: #05070a !important; border: 1px solid var(--border) !important; border-radius: 12px !important; }

        /* Custom header bar */
        .tb-appbar {
            display:flex; align-items:center; justify-content:space-between;
            padding: 14px 20px; margin-bottom: 14px;
            background: linear-gradient(90deg, var(--panel) 0%, var(--panel-2) 100%);
            border: 1px solid var(--border); border-radius: 16px;
        }
        .tb-brand { display:flex; align-items:center; gap:12px; }
        .tb-logo { font-size: 1.6rem; }
        .tb-title { font-size: 1.15rem; font-weight: 700; color: var(--text); line-height:1.1; }
        .tb-sub { font-size: 0.75rem; color: var(--muted); }
        .tb-pill { padding: 6px 14px; border-radius: 999px; font-size: 0.78rem; font-weight: 600; border:1px solid var(--border); }
        .tb-pill.ok { color: var(--accent); background: rgba(0,208,156,0.10); border-color: rgba(0,208,156,0.35); }
        .tb-pill.bad { color: var(--accent-red); background: rgba(246,70,93,0.10); border-color: rgba(246,70,93,0.35); }
        .tb-pill.warn { color: var(--accent-amber); background: rgba(240,185,11,0.10); border-color: rgba(240,185,11,0.35); }
        .tb-section { font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.12em; color: var(--muted); margin: 8px 0 2px; }
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


def post_start(account_id: str | None) -> requests.Response:
    params = {"accountId": account_id} if account_id else None
    return _request_with_retry(
        "POST", f"{API_BASE_URL}/start", params=params, headers=_auth_headers()
    )


def post_stop() -> requests.Response:
    return _request_with_retry("POST", f"{API_BASE_URL}/stop", headers=_auth_headers())


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
    st.markdown('<div class="tb-section">MT5 Broker Connection</div>', unsafe_allow_html=True)
    with st.form("mt5_form"):
        mt5_login = st.text_input("Account Number", value="", placeholder="e.g. 51234567")
        mt5_password = st.text_input("Password", value="", type="password")
        mt5_server = st.text_input("Server Name", value="", placeholder="e.g. The5ers-Live")
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
                else:
                    msg = _format_api_error(resp, f"HTTP {resp.status_code}")
                    st.error(f"Connect failed: {msg}")
            except requests.RequestException as exc:
                st.error(_friendly_network_error(exc))

    if st.session_state.get("mt5_connected"):
        st.caption("MT5 credentials configured ✅")

with st.sidebar:
    st.markdown('<div class="tb-section">AI Risk Guardrails</div>', unsafe_allow_html=True)
    st.caption("Define boundaries; the AI agent trades autonomously within them.")
    with st.form("guardrails_form"):
        autonomous_enabled = st.checkbox("Enable Autonomous AI Trading", value=False)
        max_risk_percent = st.number_input(
            "Max Risk Per Trade (%)", min_value=0.1, max_value=100.0, value=1.0, step=0.1
        )
        allowed_symbols_raw = st.text_input(
            "Allowed Symbols (comma-separated)", value="EURUSD, XAUUSD"
        )
        max_drawdown_usd = st.number_input(
            "Max Drawdown (USD)", min_value=1.0, value=500.0, step=10.0
        )
        gr_stop_loss_pips = st.number_input(
            "Stop Loss (pips)", min_value=1, max_value=1000, value=20, step=1, key="gr_sl"
        )
        gr_take_profit_pips = st.number_input(
            "Take Profit (pips)", min_value=1, max_value=2000, value=40, step=1, key="gr_tp"
        )
        account_balance_usd = st.number_input(
            "Account Balance (USD)", min_value=1.0, value=10000.0, step=100.0
        )
        guardrails_submit = st.form_submit_button(
            "Save Risk Guardrails", use_container_width=True
        )

    if guardrails_submit:
        symbols = [s.strip().upper() for s in allowed_symbols_raw.split(",") if s.strip()]
        guardrails_payload = {
            "maxRiskPercent": float(max_risk_percent),
            "allowedSymbols": symbols,
            "maxDrawdownUsd": float(max_drawdown_usd),
            "stopLossPips": int(gr_stop_loss_pips),
            "takeProfitPips": int(gr_take_profit_pips),
            "accountBalanceUsd": float(account_balance_usd),
            "autonomousEnabled": bool(autonomous_enabled),
        }
        try:
            resp = post_guardrails(guardrails_payload)
            if resp.ok:
                mode = "AUTONOMOUS" if autonomous_enabled else "manual"
                st.success(f"Guardrails saved ({mode} mode)")
            else:
                msg = _format_api_error(resp, f"HTTP {resp.status_code}")
                st.error(f"Guardrails rejected: {msg}")
        except requests.RequestException as exc:
            st.error(_friendly_network_error(exc))

st.subheader("Autonomous Engine")
start_col, stop_col, refresh_col = st.columns(3)

if start_col.button("Start AI", use_container_width=True):
    try:
        resp = post_start(st.session_state.get("account_id"))
        if resp.ok:
            st.session_state["last_status"] = resp.json()
            st.success("AI engine started")
        else:
            msg = _format_api_error(resp, f"HTTP {resp.status_code}")
            st.error(f"Start failed: {msg}")
    except requests.RequestException as exc:
        st.error(_friendly_network_error(exc))

if stop_col.button("Stop AI", use_container_width=True):
    try:
        resp = post_stop()
        if resp.ok:
            st.session_state["last_status"] = resp.json()
            st.success("AI engine stopped")
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
st.subheader("AI Activity Console")
st.caption("Live stream of the LLM's market analysis, reasoning and BUY / SELL / HOLD decisions.")

_DECISION_ICON = {"BUY": "🟢", "SELL": "🔴", "HOLD": "⚪", "REJECTED": "⛔"}

activity = get_activity(limit=40)
if activity:
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


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

import requests
import streamlit as st

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


def _url(path: str) -> str:
    """Join the API base with a relative path."""
    return f"{API_BASE_URL.rstrip('/')}/{path.lstrip('/')}"


def _auth_url(path: str) -> str:
    """Join the auth base with a relative path."""
    return f"{AUTH_BASE_URL.rstrip('/')}/{path.lstrip('/')}"


def _auth_headers() -> dict[str, str]:
    """Bearer-token header for authenticated backend calls."""
    token = st.session_state.get("auth_token")
    return {"Authorization": f"Bearer {token}"} if token else {}


def login(username: str, password: str) -> tuple[bool, str]:
    """Authenticate against the backend and store the bearer token."""
    try:
        resp = requests.post(
            _auth_url("login"),
            json={"username": username, "password": password},
            timeout=REQUEST_TIMEOUT,
        )
        if resp.ok:
            data = resp.json()
            st.session_state["auth_token"] = data.get("token")
            st.session_state["auth_user"] = data.get("username", username)
            st.session_state["auth_expires"] = data.get("expiresAt")
            return True, "Login successful"
        if resp.status_code == 401:
            return False, "Invalid username or password"
        return False, f"Login failed (HTTP {resp.status_code})"
    except requests.RequestException as exc:
        return False, f"Failed to reach backend: {exc}"


def logout() -> None:
    """Revoke the token on the backend and clear the local session."""
    token = st.session_state.get("auth_token")
    if token:
        try:
            requests.post(
                _auth_url("logout"),
                headers={"Authorization": f"Bearer {token}"},
                timeout=REQUEST_TIMEOUT,
            )
        except requests.RequestException:
            pass
    for key in ("auth_token", "auth_user", "auth_expires", "last_status", "account_id"):
        st.session_state.pop(key, None)


def get_health() -> tuple[bool, str]:
    """Return (is_up, message) for the backend health endpoint."""
    try:
        resp = requests.get(_url("health"), timeout=REQUEST_TIMEOUT)
        if resp.ok:
            return True, resp.text.strip()
        return False, f"HTTP {resp.status_code}"
    except requests.RequestException as exc:
        return False, str(exc)


def post_start(account_id: str | None) -> requests.Response:
    """Engage the autonomous AI trading engine."""
    params = {"accountId": account_id} if account_id else None
    return requests.post(
        _url("start"), params=params, headers=_auth_headers(), timeout=REQUEST_TIMEOUT
    )


def post_stop() -> requests.Response:
    """Halt the autonomous AI trading engine."""
    return requests.post(_url("stop"), headers=_auth_headers(), timeout=REQUEST_TIMEOUT)


def get_status() -> requests.Response:
    """Fetch the current bot status snapshot."""
    return requests.get(_url("status"), headers=_auth_headers(), timeout=REQUEST_TIMEOUT)


def _mt5_url(path: str) -> str:
    base = os.getenv("FOREXBOT_MT5_URL", f"{BACKEND_BASE_URL}/api/mt5")
    return f"{base.rstrip('/')}/{path.lstrip('/')}"


def _risk_url(path: str) -> str:
    base = os.getenv("FOREXBOT_RISK_URL", f"{BACKEND_BASE_URL}/api/risk")
    return f"{base.rstrip('/')}/{path.lstrip('/')}"


def _monitor_url(path: str) -> str:
    base = os.getenv("FOREXBOT_MONITOR_URL", f"{BACKEND_BASE_URL}/api/monitor")
    return f"{base.rstrip('/')}/{path.lstrip('/')}"


def get_telemetry() -> dict[str, Any] | None:
    """Fetch live bridge/account telemetry (balance, equity, connection)."""
    try:
        resp = requests.get(_monitor_url("telemetry"), headers=_auth_headers(),
                            timeout=REQUEST_TIMEOUT)
        return resp.json() if resp.ok else None
    except requests.RequestException:
        return None


def get_positions() -> list[dict[str, Any]]:
    """Fetch open positions (for aggregate open P&L)."""
    try:
        resp = requests.get(_monitor_url("positions"), headers=_auth_headers(),
                            timeout=REQUEST_TIMEOUT)
        return resp.json() if resp.ok else []
    except requests.RequestException:
        return []


def get_activity(limit: int = 40) -> list[dict[str, Any]]:
    """Fetch the LLM activity / decision feed."""
    try:
        resp = requests.get(_monitor_url("activity"), params={"limit": limit},
                            headers=_auth_headers(), timeout=REQUEST_TIMEOUT)
        return resp.json() if resp.ok else []
    except requests.RequestException:
        return []


def post_mt5_connect(login: int, password: str, server: str) -> requests.Response:
    """Send MT5 credentials to the backend for dynamic routing."""
    payload = {"login": int(login), "password": password, "server": server}
    return requests.post(
        _mt5_url("connect"), json=payload, headers=_auth_headers(), timeout=REQUEST_TIMEOUT
    )


def post_guardrails(guardrails: dict[str, Any]) -> requests.Response:
    """Send the risk guardrails configuration to the backend."""
    return requests.post(
        _risk_url("guardrails"), json=guardrails, headers=_auth_headers(),
        timeout=REQUEST_TIMEOUT,
    )


st.set_page_config(
    page_title="AI Forex Trading Bot",
    page_icon="📈",
    layout="centered",
    initial_sidebar_state="collapsed",
)
st.title("AI Forex Trading Bot - Monitoring Hub")


def render_login() -> None:
    """Render the login gate that guards the dashboard."""
    st.subheader("Sign in")
    st.info("Authentication required to access the trading dashboard.")
    with st.form("login_form"):
        username = st.text_input("Username", value="", autocomplete="username")
        password = st.text_input(
            "Password", value="", type="password", autocomplete="current-password"
        )
        submit = st.form_submit_button("Login", use_container_width=True)
    if submit:
        ok, message = login(username, password)
        if ok:
            st.success(message)
            st.rerun()
        else:
            st.error(message)


# --- Authentication gate: nothing below renders until logged in. ---
if not st.session_state.get("auth_token"):
    render_login()
    st.stop()

with st.sidebar:
    st.caption(f"Signed in as **{st.session_state.get('auth_user', 'user')}**")
    if st.session_state.get("auth_expires"):
        st.caption(f"Session expires: {st.session_state['auth_expires']}")
    if st.button("Log out", use_container_width=True):
        logout()
        st.rerun()

healthy, health_msg = get_health()
if healthy:
    st.success(f"Backend reachable at {API_BASE_URL} (health: {health_msg})")
else:
    st.error(f"Backend unreachable at {API_BASE_URL} - {health_msg}")

with st.sidebar:
    st.header("MT5 Broker Connection")
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
                    st.error(f"Connect failed (HTTP {resp.status_code}): {resp.text}")
            except requests.RequestException as exc:
                st.error(f"Failed to reach backend: {exc}")

    if st.session_state.get("mt5_connected"):
        st.caption("MT5 credentials configured ✅")

with st.sidebar:
    st.header("AI Risk Guardrails")
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
                st.error(f"Guardrails rejected (HTTP {resp.status_code}): {resp.text}")
        except requests.RequestException as exc:
            st.error(f"Failed to reach backend: {exc}")

st.subheader("Autonomous Engine")
start_col, stop_col, refresh_col = st.columns(3)

if start_col.button("Start AI", use_container_width=True):
    try:
        resp = post_start(st.session_state.get("account_id"))
        if resp.ok:
            st.session_state["last_status"] = resp.json()
            st.success("AI engine started")
        else:
            st.error(f"Start failed (HTTP {resp.status_code}): {resp.text}")
    except requests.RequestException as exc:
        st.error(f"Failed to reach backend: {exc}")

if stop_col.button("Stop AI", use_container_width=True):
    try:
        resp = post_stop()
        if resp.ok:
            st.session_state["last_status"] = resp.json()
            st.success("AI engine stopped")
        else:
            st.error(f"Stop failed (HTTP {resp.status_code}): {resp.text}")
    except requests.RequestException as exc:
        st.error(f"Failed to reach backend: {exc}")

if refresh_col.button("Refresh", use_container_width=True):
    try:
        resp = get_status()
        if resp.ok:
            st.session_state["last_status"] = resp.json()
        else:
            st.error(f"Status failed (HTTP {resp.status_code}): {resp.text}")
    except requests.RequestException as exc:
        st.error(f"Failed to reach backend: {exc}")

st.divider()
st.subheader("Live Telemetry")

telemetry = get_telemetry()
connected = bool(telemetry.get("connected")) if telemetry else False
account = (telemetry or {}).get("account") or {}
positions = get_positions()
open_pnl = sum(float(p.get("profit", 0.0)) for p in positions)

if connected:
    st.success("🟢 Bridge Connected")
else:
    st.error("🔴 Bridge Disconnected")

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


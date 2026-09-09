"""JTICKET-07 | Streamlit UI Connector & API Integration.

Streamlit frontend that communicates with the Java Spring Boot backend
exposed at http://localhost:8080/api/bot/...

Features:
  * Sidebar parameter form that POSTs a BotConfig to /api/bot/config.
  * Start / Stop lifecycle buttons wired to /api/bot/start and /api/bot/stop.
  * Live status panel backed by /api/bot/status.
  * Backend health indicator backed by /api/bot/health.
  * Optional manual market-tick sender wired to /api/bot/tick.

Run with:
    streamlit run frontend/streamlit_app.py
"""

from __future__ import annotations

import os
from typing import Any

import requests
import streamlit as st

API_BASE_URL = os.getenv("FOREXBOT_API_URL", "http://localhost:8080/api/bot")
AUTH_BASE_URL = os.getenv("FOREXBOT_AUTH_URL", "http://localhost:8080/api/auth")
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


def post_config(config: dict[str, Any]) -> requests.Response:
    """POST the bot configuration to the backend."""
    return requests.post(
        _url("config"), json=config, headers=_auth_headers(), timeout=REQUEST_TIMEOUT
    )


def post_start(account_id: str | None) -> requests.Response:
    """Trigger the bot start command."""
    params = {"accountId": account_id} if account_id else None
    return requests.post(
        _url("start"), params=params, headers=_auth_headers(), timeout=REQUEST_TIMEOUT
    )


def post_stop() -> requests.Response:
    """Trigger the bot stop command."""
    return requests.post(_url("stop"), headers=_auth_headers(), timeout=REQUEST_TIMEOUT)


def get_status() -> requests.Response:
    """Fetch the current bot status snapshot."""
    return requests.get(_url("status"), headers=_auth_headers(), timeout=REQUEST_TIMEOUT)


def post_tick(tick: dict[str, Any]) -> requests.Response:
    """Send a market tick through the pipeline."""
    return requests.post(
        _url("tick"), json=tick, headers=_auth_headers(), timeout=REQUEST_TIMEOUT
    )


def render_status(status: dict[str, Any]) -> None:
    """Render a BotStatus payload as metrics."""
    col1, col2, col3 = st.columns(3)
    running = status.get("running", False)
    col1.metric("State", "RUNNING" if running else "STOPPED")
    col2.metric("Trades Today", status.get("tradesExecutedToday", 0))
    col3.metric("Daily P&L (USD)", f"{status.get('dailyPnlUsd', 0.0):,.2f}")
    st.caption(
        f"Account: {status.get('accountId') or 'n/a'}  |  "
        f"Last updated: {status.get('lastUpdated') or 'n/a'}"
    )


st.set_page_config(page_title="AI Forex Trading Bot", layout="wide")
st.title("AI Forex Trading Bot - Control Panel")


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
                else:
                    st.error(f"Connect failed (HTTP {resp.status_code}): {resp.text}")
            except requests.RequestException as exc:
                st.error(f"Failed to reach backend: {exc}")

    if st.session_state.get("mt5_connected"):
        st.caption("MT5 credentials configured ✅")

with st.sidebar:
    st.header("Bot Configuration")

    with st.form("config_form"):
        account_id = st.text_input("Account ID", value="ACC-001")
        currency_pair = st.selectbox(
            "Currency Pair",
            options=["EUR/USD", "GBP/USD", "USD/JPY", "AUD/USD", "USD/CHF"],
            index=0,
        )
        max_daily_trades = st.number_input(
            "Max Daily Trades", min_value=1, max_value=1000, value=10, step=1
        )
        max_daily_loss_usd = st.number_input(
            "Max Daily Loss (USD)", min_value=0.01, value=500.0, step=10.0
        )
        risk_per_trade_percent = st.number_input(
            "Risk Per Trade (%)", min_value=0.01, max_value=100.0, value=1.0, step=0.1
        )
        stop_loss_pips = st.number_input(
            "Stop Loss (pips)", min_value=1, max_value=1000, value=20, step=1
        )

        submitted = st.form_submit_button("Apply Configuration", use_container_width=True)

    if submitted:
        payload = {
            "accountId": account_id,
            "currencyPair": currency_pair,
            "maxDailyTrades": int(max_daily_trades),
            "maxDailyLossUsd": float(max_daily_loss_usd),
            "riskPerTradePercent": float(risk_per_trade_percent),
            "stopLossPips": int(stop_loss_pips),
        }
        try:
            resp = post_config(payload)
            if resp.ok:
                st.success("Configuration applied")
                st.session_state["last_status"] = resp.json()
            else:
                st.error(f"Config rejected (HTTP {resp.status_code}): {resp.text}")
        except requests.RequestException as exc:
            st.error(f"Failed to reach backend: {exc}")

    st.session_state["account_id"] = account_id

st.subheader("Lifecycle Controls")
start_col, stop_col, refresh_col = st.columns(3)

if start_col.button("Start Bot", use_container_width=True):
    try:
        resp = post_start(st.session_state.get("account_id"))
        if resp.ok:
            st.session_state["last_status"] = resp.json()
            st.success("Start command sent")
        else:
            st.error(f"Start failed (HTTP {resp.status_code}): {resp.text}")
    except requests.RequestException as exc:
        st.error(f"Failed to reach backend: {exc}")

if stop_col.button("Stop Bot", use_container_width=True):
    try:
        resp = post_stop()
        if resp.ok:
            st.session_state["last_status"] = resp.json()
            st.success("Stop command sent")
        else:
            st.error(f"Stop failed (HTTP {resp.status_code}): {resp.text}")
    except requests.RequestException as exc:
        st.error(f"Failed to reach backend: {exc}")

if refresh_col.button("Refresh Status", use_container_width=True):
    try:
        resp = get_status()
        if resp.ok:
            st.session_state["last_status"] = resp.json()
        else:
            st.error(f"Status failed (HTTP {resp.status_code}): {resp.text}")
    except requests.RequestException as exc:
        st.error(f"Failed to reach backend: {exc}")

st.subheader("Bot Status")
status = st.session_state.get("last_status")
if status is None:
    try:
        resp = get_status()
        if resp.ok:
            status = resp.json()
            st.session_state["last_status"] = status
    except requests.RequestException:
        status = None

if status:
    render_status(status)
else:
    st.info("No status yet. Apply a configuration or start the bot.")

with st.expander("Send a Market Tick (manual test)"):
    with st.form("tick_form"):
        tick_pair = st.text_input("Currency Pair", value="EUR/USD", key="tick_pair")
        bid = st.number_input("Bid", min_value=0.0001, value=1.0850, step=0.0001, format="%.5f")
        ask = st.number_input("Ask", min_value=0.0001, value=1.0852, step=0.0001, format="%.5f")
        tick_submit = st.form_submit_button("Send Tick")

    if tick_submit:
        tick_payload = {"currencyPair": tick_pair, "bid": float(bid), "ask": float(ask)}
        try:
            resp = post_tick(tick_payload)
            if resp.status_code == 423:
                st.warning("Tick blocked by risk firewall")
                st.json(resp.json())
            elif resp.ok:
                st.success("Tick processed")
                st.json(resp.json())
            else:
                st.error(f"Tick failed (HTTP {resp.status_code}): {resp.text}")
        except requests.RequestException as exc:
            st.error(f"Failed to reach backend: {exc}")


"""
MT5 Execution & Data Bridge
===========================

Local Python bridge that connects the Java Spring Boot backend / LLM pipeline to
an active MetaTrader 5 terminal (targeting prop-firm environments such as
The5ers). Handles dynamic credential initialization, live market-data retrieval
for the AI engine, and fully automated order execution.

Public core functions (per ticket):
    * initialize_mt5(login, password, server) -> dynamic connection.
    * get_market_data(symbol, timeframe, n_bars) -> live price history.
    * execute_ai_trade(signal) -> automated order placement.

Security:
    * Binds to localhost only.
    * Requires a bearer token (MT5_BRIDGE_API_KEY) matching the backend.

Endpoints:
    GET  /health   -> terminal / connection status
    GET  /quote    -> latest tick for a symbol
    GET  /candles  -> recent OHLC bars for the LLM analysis loop
    POST /order    -> execute an AI-generated market order

Run:
    pip install -r requirements.txt
    python mt5_bridge.py
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import random
import threading
from datetime import datetime, timezone
from functools import wraps

from flask import Flask, jsonify, request

try:  # MetaTrader5 only installs on Windows terminals.
    import MetaTrader5 as mt5
except Exception:  # pragma: no cover - allows import on non-Windows dev boxes.
    mt5 = None

try:  # websockets powers the cloud-to-local execution pipeline.
    import websockets
except Exception:  # pragma: no cover - optional at import time.
    websockets = None

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] mt5_bridge: %(message)s",
)
log = logging.getLogger("mt5_bridge")

app = Flask(__name__)

API_KEY = os.getenv("MT5_BRIDGE_API_KEY", "")
HOST = os.getenv("MT5_BRIDGE_HOST", "127.0.0.1")
PORT = int(os.getenv("MT5_BRIDGE_PORT", "8090"))

# Optional MT5 login (falls back to the already-attached terminal session).
MT5_LOGIN = os.getenv("MT5_LOGIN")
MT5_PASSWORD = os.getenv("MT5_PASSWORD")
MT5_SERVER = os.getenv("MT5_SERVER")
MT5_PATH = os.getenv("MT5_TERMINAL_PATH")

# Cloud backend WebSocket endpoint the bridge connects out to (behind NAT).
BACKEND_WS_URL = os.getenv("BACKEND_WS_URL", "")
BRIDGE_ID = os.getenv("BRIDGE_ID", "local-mt5-bridge")
TELEMETRY_INTERVAL = int(os.getenv("TELEMETRY_INTERVAL", "15"))

# Map string timeframes from the backend to MT5 constants (resolved lazily).
_TIMEFRAME_NAMES = {
    "M1": "TIMEFRAME_M1",
    "M5": "TIMEFRAME_M5",
    "M15": "TIMEFRAME_M15",
    "M30": "TIMEFRAME_M30",
    "H1": "TIMEFRAME_H1",
    "H4": "TIMEFRAME_H4",
    "D1": "TIMEFRAME_D1",
}


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _resolve_timeframe(timeframe: str):
    """Translate a string timeframe (e.g. 'M15') to an MT5 constant."""
    name = _TIMEFRAME_NAMES.get((timeframe or "M15").upper(), "TIMEFRAME_M15")
    return getattr(mt5, name)


# --------------------------------------------------------------------------- #
# 1. Connection Initialization Module
# --------------------------------------------------------------------------- #
def initialize_mt5(login=None, password=None, server=None) -> tuple[bool, str]:
    """Dynamically initialize the MT5 terminal connection.

    Credentials may be supplied per-request (sent by the backend via the
    execution payload); otherwise environment defaults / the attached terminal
    session are used. Robust error logging uses ``mt5.last_error()``.
    """
    if mt5 is None:
        return False, "MetaTrader5 package not available on this host"

    kwargs = {}
    if MT5_PATH:
        kwargs["path"] = MT5_PATH

    if login and password and server:
        kwargs.update(login=int(login), password=str(password), server=str(server))
    elif MT5_LOGIN and MT5_PASSWORD and MT5_SERVER:
        kwargs.update(login=int(MT5_LOGIN), password=MT5_PASSWORD, server=MT5_SERVER)

    if not mt5.initialize(**kwargs):
        code, msg = mt5.last_error()
        log.error("MT5 initialize failed (%s): %s", code, msg)
        return False, f"MT5 initialize failed ({code}): {msg}"

    log.info("MT5 initialized (login=%s server=%s)", login or MT5_LOGIN, server or MT5_SERVER)
    return True, "MT5 connected"


def ensure_mt5(credentials: dict | None = None) -> tuple[bool, str]:
    """Convenience wrapper that unpacks a credentials dict for initialize_mt5."""
    credentials = credentials or {}
    return initialize_mt5(
        login=credentials.get("login"),
        password=credentials.get("password"),
        server=credentials.get("server"),
    )


def require_token(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if API_KEY:
            header = request.headers.get("Authorization", "")
            token = header[7:].strip() if header.lower().startswith("bearer ") else ""
            if token != API_KEY:
                return jsonify({"accepted": False, "status": "UNAUTHORIZED",
                                "message": "Invalid bridge token"}), 401
        return fn(*args, **kwargs)

    return wrapper


def _pip_size(symbol_info) -> float:
    """A pip is 10 points for 5/3-digit symbols, otherwise 1 point."""
    point = symbol_info.point
    if symbol_info.digits in (3, 5):
        return point * 10
    return point


def _retcode_reason(retcode) -> str:
    """Map an MT5 order retcode to a human-readable reason for the dashboard."""
    if mt5 is None:
        return "unknown"
    reasons = {
        mt5.TRADE_RETCODE_DONE: "Order completed",
        mt5.TRADE_RETCODE_REQUOTE: "Requote",
        mt5.TRADE_RETCODE_REJECT: "Request rejected",
        mt5.TRADE_RETCODE_CANCEL: "Request canceled",
        mt5.TRADE_RETCODE_INVALID: "Invalid request",
        mt5.TRADE_RETCODE_INVALID_VOLUME: "Invalid volume/lot size",
        mt5.TRADE_RETCODE_INVALID_PRICE: "Invalid price",
        mt5.TRADE_RETCODE_INVALID_STOPS: "Invalid stops (SL/TP)",
        mt5.TRADE_RETCODE_NO_MONEY: "Insufficient funds",
        mt5.TRADE_RETCODE_MARKET_CLOSED: "Market closed",
        mt5.TRADE_RETCODE_TRADE_DISABLED: "Trading disabled",
        mt5.TRADE_RETCODE_PRICE_CHANGED: "Price changed",
        mt5.TRADE_RETCODE_PRICE_OFF: "No quotes to process request",
        mt5.TRADE_RETCODE_CONNECTION: "No connection to trade server",
        mt5.TRADE_RETCODE_TIMEOUT: "Request timed out",
    }
    return reasons.get(retcode, f"Broker retcode {retcode}")


def account_snapshot() -> dict:
    """Return live account balance/equity/margin levels for telemetry."""
    if mt5 is None:
        return {}
    info = mt5.account_info()
    if info is None:
        return {}
    return {
        "login": info.login,
        "balance": info.balance,
        "equity": info.equity,
        "margin": info.margin,
        "freeMargin": info.margin_free,
        "marginLevel": info.margin_level,
        "server": info.server,
    }


# --------------------------------------------------------------------------- #
# 2. Live Market Data Retrieval
# --------------------------------------------------------------------------- #
def get_market_data(symbol: str, timeframe: str = "M15", n_bars: int = 100):
    """Stream recent OHLC price history for LLM analysis.

    Uses ``mt5.copy_rates_from_pos()`` to pull the latest ``n_bars`` candles.
    Returns a list of dicts; raises no exceptions (returns [] on failure).
    """
    if mt5 is None:
        return []
    if not mt5.symbol_info(symbol):
        log.warning("get_market_data: unknown symbol %s", symbol)
        return []
    mt5.symbol_select(symbol, True)

    rates = mt5.copy_rates_from_pos(symbol, _resolve_timeframe(timeframe), 0, int(n_bars))
    if rates is None:
        code, msg = mt5.last_error()
        log.error("copy_rates_from_pos failed for %s (%s): %s", symbol, code, msg)
        return []

    candles = []
    for r in rates:
        candles.append({
            "time": datetime.fromtimestamp(int(r["time"]), tz=timezone.utc).isoformat(),
            "open": float(r["open"]),
            "high": float(r["high"]),
            "low": float(r["low"]),
            "close": float(r["close"]),
            "tickVolume": int(r["tick_volume"]),
        })
    return candles


# --------------------------------------------------------------------------- #
# 3. Automated Order Execution Function
# --------------------------------------------------------------------------- #
def execute_ai_trade(signal: dict) -> tuple[dict, int]:
    """Process a structured JSON signal and place a market order automatically.

    Expected signal keys: symbol, action/side (BUY/SELL), volume, and optional
    sl/tp (absolute prices) or stopLossPips/takeProfitPips (pip distances),
    plus maxSlippagePoints, magicNumber, comment and credentials.

    Returns a (response_dict, http_status) tuple.
    """
    symbol = signal.get("symbol")
    side = (signal.get("action") or signal.get("side") or "").upper()
    volume = float(signal.get("volume") or 0)

    if not symbol or side not in ("BUY", "SELL") or volume <= 0:
        return ({"accepted": False, "status": "INVALID_REQUEST",
                 "message": "symbol, action (BUY/SELL) and positive volume are required",
                 "timestamp": _now_iso()}, 400)

    ok, msg = ensure_mt5(signal.get("credentials"))
    if not ok:
        return ({"accepted": False, "status": "CONNECTION_ERROR",
                 "message": msg, "timestamp": _now_iso()}, 503)

    symbol_info = mt5.symbol_info(symbol)
    if symbol_info is None:
        return ({"accepted": False, "status": "SYMBOL_NOT_FOUND",
                 "message": f"Unknown symbol {symbol}", "timestamp": _now_iso()}, 400)
    if not symbol_info.visible:
        mt5.symbol_select(symbol, True)

    tick = mt5.symbol_info_tick(symbol)
    if tick is None:
        return ({"accepted": False, "status": "NO_QUOTE",
                 "message": f"No live quote for {symbol}", "timestamp": _now_iso()}, 503)

    pip = _pip_size(symbol_info)
    deviation = int(signal.get("maxSlippagePoints") or 20)
    magic = int(signal.get("magicNumber") or 0)
    comment = (signal.get("comment") or "forexbot")[:31]

    if side == "BUY":
        order_type = mt5.ORDER_TYPE_BUY
        price = tick.ask
    else:
        order_type = mt5.ORDER_TYPE_SELL
        price = tick.bid

    # Prefer explicit SL/TP prices, else derive from pip distances.
    sl = signal.get("sl")
    tp = signal.get("tp")
    if sl is None:
        sl_pips = int(signal.get("stopLossPips") or 0)
        if sl_pips:
            sl = price - sl_pips * pip if side == "BUY" else price + sl_pips * pip
    if tp is None:
        tp_pips = int(signal.get("takeProfitPips") or 0)
        if tp_pips:
            tp = price + tp_pips * pip if side == "BUY" else price - tp_pips * pip

    request_payload = {
        "action": mt5.TRADE_ACTION_DEAL,
        "symbol": symbol,
        "volume": volume,
        "type": order_type,
        "price": price,
        "sl": round(float(sl), symbol_info.digits) if sl else 0.0,
        "tp": round(float(tp), symbol_info.digits) if tp else 0.0,
        "deviation": deviation,
        "magic": magic,
        "comment": comment,
        "type_time": mt5.ORDER_TIME_GTC,
        "type_filling": mt5.ORDER_FILLING_IOC,
    }

    log.info("Sending %s %s %.2f lots @ %.5f (SL=%s TP=%s)",
             side, symbol, volume, price, request_payload["sl"], request_payload["tp"])
    try:
        result = mt5.order_send(request_payload)
    except Exception as exc:  # terminal crash / IPC failure
        log.exception("order_send raised an exception for %s %s", side, symbol)
        return ({"accepted": False, "status": "EXECUTION_EXCEPTION",
                 "message": f"order_send exception: {exc}", "timestamp": _now_iso()}, 500)

    if result is None:
        code, err = mt5.last_error()
        log.error("order_send returned None (%s): %s", code, err)
        return ({"accepted": False, "status": "SEND_FAILED",
                 "brokerRetcode": code, "message": err, "timestamp": _now_iso()}, 502)

    accepted = result.retcode == mt5.TRADE_RETCODE_DONE
    slippage_pips = abs(result.price - price) / pip if pip else 0.0
    reason = _retcode_reason(result.retcode)

    response = {
        "accepted": accepted,
        "status": "FILLED" if accepted else "REJECTED",
        "brokerRetcode": result.retcode,
        "brokerReason": reason,
        "orderTicket": getattr(result, "order", None),
        "executedPrice": result.price,
        "executedVolume": result.volume,
        "slippagePips": round(slippage_pips, 2),
        "message": result.comment,
        "account": account_snapshot(),
        "timestamp": _now_iso(),
    }

    if accepted:
        log.info("FILLED ticket=%s price=%.5f slippage=%.2f pips",
                 response["orderTicket"], result.price, slippage_pips)
    else:
        # Specific handling for common broker rejections (requote / closed market).
        log.warning("REJECTED retcode=%s (%s) comment=%s",
                    result.retcode, reason, result.comment)

    return response, (200 if accepted else 422)


# --------------------------------------------------------------------------- #
# 4. Cloud-to-Local WebSocket Execution Pipeline
# --------------------------------------------------------------------------- #
# The bridge runs behind NAT, so it dials *out* to the cloud backend, receives
# risk-validated trade commands, dispatches them to MT5, and streams execution
# receipts + account telemetry back. The loop is fully self-healing: any network
# disruption triggers an exponential-backoff reconnect instead of crashing.

_RECONNECT_BASE_DELAY = 2       # seconds
_RECONNECT_MAX_DELAY = 60       # seconds


def _build_receipt(command: dict, response: dict, status: int) -> dict:
    """Wrap an execution response into a telemetry receipt for the dashboard."""
    return {
        "type": "EXECUTION_RECEIPT",
        "bridgeId": BRIDGE_ID,
        "commandId": command.get("commandId") or command.get("id"),
        "httpStatus": status,
        "result": response,
        "orderTicket": response.get("orderTicket"),
        "account": response.get("account") or account_snapshot(),
        "timestamp": _now_iso(),
    }


def _build_telemetry() -> dict:
    """Periodic account/margin snapshot pushed to the cloud dashboard."""
    return {
        "type": "TELEMETRY",
        "bridgeId": BRIDGE_ID,
        "account": account_snapshot(),
        "connected": mt5 is not None and mt5.terminal_info() is not None,
        "timestamp": _now_iso(),
    }


async def _safe_send(ws, payload: dict) -> None:
    """Send a JSON payload, swallowing transient send failures."""
    try:
        await ws.send(json.dumps(payload))
    except Exception as exc:  # pragma: no cover - network dependent
        log.warning("Failed to send payload to backend: %s", exc)


async def _handle_command(ws, raw_message: str) -> None:
    """Parse an incoming command, dispatch to MT5, and emit a receipt."""
    try:
        command = json.loads(raw_message)
    except (TypeError, ValueError) as exc:
        log.error("Discarding malformed command payload: %s", exc)
        await _safe_send(ws, {
            "type": "EXECUTION_RECEIPT",
            "bridgeId": BRIDGE_ID,
            "httpStatus": 400,
            "result": {"accepted": False, "status": "INVALID_JSON",
                       "message": f"Malformed command: {exc}"},
            "timestamp": _now_iso(),
        })
        return

    msg_type = (command.get("type") or "ORDER").upper()

    # Allow the backend to poll telemetry / keep-alive on demand.
    if msg_type in ("PING", "TELEMETRY_REQUEST"):
        await _safe_send(ws, _build_telemetry())
        return

    # Normalize the backend 'side' field into the shared signal schema.
    if "action" not in command and "side" in command:
        command["action"] = command.get("side")

    try:
        response, status = execute_ai_trade(command)
    except Exception as exc:  # never let a single bad command kill the worker
        log.exception("Unhandled error while executing command")
        response, status = ({"accepted": False, "status": "EXECUTION_EXCEPTION",
                             "message": str(exc), "timestamp": _now_iso()}, 500)

    await _safe_send(ws, _build_receipt(command, response, status))


async def _telemetry_pump(ws) -> None:
    """Continuously stream account/margin telemetry back to the cloud."""
    while True:
        await asyncio.sleep(TELEMETRY_INTERVAL)
        await _safe_send(ws, _build_telemetry())


async def _run_ws_session(url: str) -> None:
    """Open a single WebSocket session and service commands until it drops."""
    headers = [("Authorization", f"Bearer {API_KEY}")] if API_KEY else []
    async with websockets.connect(url, extra_headers=headers,
                                  ping_interval=20, ping_timeout=20) as ws:
        log.info("Connected to backend WebSocket %s", url)
        # Announce presence so the backend can route commands to this bridge.
        await _safe_send(ws, {
            "type": "HELLO",
            "bridgeId": BRIDGE_ID,
            "account": account_snapshot(),
            "timestamp": _now_iso(),
        })

        telemetry_task = asyncio.ensure_future(_telemetry_pump(ws))
        try:
            async for raw_message in ws:
                await _handle_command(ws, raw_message)
        finally:
            telemetry_task.cancel()


async def _ws_client_loop() -> None:
    """Auto-reconnecting client loop with exponential backoff resilience."""
    delay = _RECONNECT_BASE_DELAY
    while True:
        try:
            await _run_ws_session(BACKEND_WS_URL)
            # Clean disconnect -> reset backoff before reconnecting.
            delay = _RECONNECT_BASE_DELAY
            log.info("WebSocket session closed; reconnecting in %ss", delay)
        except asyncio.CancelledError:  # pragma: no cover - shutdown path
            raise
        except Exception as exc:  # network drop, handshake failure, etc.
            log.warning("WebSocket session ended (%s); reconnecting in %ss",
                        exc, delay)

        # Jittered exponential backoff to avoid thundering-herd reconnects.
        await asyncio.sleep(delay + random.uniform(0, 1))
        delay = min(delay * 2, _RECONNECT_MAX_DELAY)


def start_ws_bridge() -> None:
    """Spin up the WebSocket execution pipeline on a background thread."""
    if not BACKEND_WS_URL:
        log.info("BACKEND_WS_URL not set; WebSocket execution pipeline disabled")
        return
    if websockets is None:
        log.error("websockets package not installed; cannot start execution pipeline")
        return

    def _runner():
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            loop.run_until_complete(_ws_client_loop())
        except Exception:  # pragma: no cover - last-resort guard
            log.exception("WebSocket client loop terminated unexpectedly")
        finally:
            loop.close()

    thread = threading.Thread(target=_runner, name="mt5-ws-bridge", daemon=True)
    thread.start()
    log.info("WebSocket execution pipeline started -> %s", BACKEND_WS_URL)


# --------------------------------------------------------------------------- #
# REST endpoints
# --------------------------------------------------------------------------- #
@app.get("/health")
def health():
    ok, msg = ensure_mt5()
    payload = {"status": "UP" if ok else "DOWN", "detail": msg, "timestamp": _now_iso()}
    if ok and mt5 is not None:
        info = mt5.account_info()
        if info is not None:
            payload["account"] = {
                "login": info.login,
                "balance": info.balance,
                "equity": info.equity,
                "server": info.server,
            }
    return jsonify(payload), (200 if ok else 503)


@app.get("/quote")
@require_token
def quote():
    """Return a live tick for a symbol so the backend can ingest market data."""
    symbol = request.args.get("symbol")
    if not symbol:
        return jsonify({"status": "INVALID_REQUEST", "message": "symbol is required"}), 400

    ok, msg = ensure_mt5()
    if not ok:
        return jsonify({"status": "CONNECTION_ERROR", "message": msg}), 503

    if not mt5.symbol_info(symbol):
        return jsonify({"status": "SYMBOL_NOT_FOUND", "message": f"Unknown symbol {symbol}"}), 400
    mt5.symbol_select(symbol, True)

    tick = mt5.symbol_info_tick(symbol)
    if tick is None:
        return jsonify({"status": "NO_QUOTE", "message": f"No live quote for {symbol}"}), 503

    return jsonify({
        "currencyPair": symbol,
        "bid": tick.bid,
        "ask": tick.ask,
        "timestamp": _now_iso(),
    }), 200


@app.get("/candles")
@require_token
def candles():
    """Return recent OHLC bars for the LLM analysis loop."""
    symbol = request.args.get("symbol")
    if not symbol:
        return jsonify({"status": "INVALID_REQUEST", "message": "symbol is required"}), 400

    ok, msg = ensure_mt5()
    if not ok:
        return jsonify({"status": "CONNECTION_ERROR", "message": msg}), 503

    timeframe = request.args.get("timeframe", "M15")
    n_bars = int(request.args.get("nBars", "100"))
    data = get_market_data(symbol, timeframe, n_bars)
    return jsonify({
        "symbol": symbol,
        "timeframe": timeframe,
        "count": len(data),
        "candles": data,
        "timestamp": _now_iso(),
    }), 200


@app.post("/order")
@require_token
def order():
    """Execute an AI-generated market order from a structured JSON signal."""
    signal = request.get_json(silent=True) or {}
    # Normalize the backend 'side' field into the shared signal schema.
    if "action" not in signal and "side" in signal:
        signal["action"] = signal.get("side")
    response, status = execute_ai_trade(signal)
    return jsonify(response), status


if __name__ == "__main__":
    log.info("Starting MT5 bridge on %s:%s", HOST, PORT)
    # Launch the cloud-to-local execution pipeline alongside the REST server.
    start_ws_bridge()
    app.run(host=HOST, port=PORT)


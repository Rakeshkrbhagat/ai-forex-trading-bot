"""
MT5 Execution Bridge
====================

A lightweight local REST bridge that receives validated order payloads from the
Java Spring Boot backend and executes market orders on an active MetaTrader 5
terminal (targeting prop-firm environments such as The5ers).

Security:
    * Binds to localhost only.
    * Requires a bearer token (MT5_BRIDGE_API_KEY) matching the backend.

Endpoints:
    GET  /health   -> terminal / connection status
    POST /order    -> execute a market order

Run:
    pip install -r requirements.txt
    python mt5_bridge.py
"""

from __future__ import annotations

import logging
import os
from datetime import datetime, timezone
from functools import wraps

from flask import Flask, jsonify, request

try:  # MetaTrader5 only installs on Windows terminals.
    import MetaTrader5 as mt5
except Exception:  # pragma: no cover - allows import on non-Windows dev boxes.
    mt5 = None

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


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def ensure_mt5() -> tuple[bool, str]:
    """Initialize the MT5 terminal connection if not already active."""
    if mt5 is None:
        return False, "MetaTrader5 package not available on this host"

    kwargs = {}
    if MT5_PATH:
        kwargs["path"] = MT5_PATH
    if MT5_LOGIN and MT5_PASSWORD and MT5_SERVER:
        kwargs.update(login=int(MT5_LOGIN), password=MT5_PASSWORD, server=MT5_SERVER)

    if not mt5.initialize(**kwargs):
        code, msg = mt5.last_error()
        return False, f"MT5 initialize failed ({code}): {msg}"
    return True, "MT5 connected"


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


@app.post("/order")
@require_token
def order():
    data = request.get_json(silent=True) or {}
    symbol = data.get("symbol")
    side = (data.get("side") or "").upper()
    volume = float(data.get("volume") or 0)

    if not symbol or side not in ("BUY", "SELL") or volume <= 0:
        return jsonify({"accepted": False, "status": "INVALID_REQUEST",
                        "message": "symbol, side (BUY/SELL) and positive volume are required",
                        "timestamp": _now_iso()}), 400

    ok, msg = ensure_mt5()
    if not ok:
        log.error("Connection error: %s", msg)
        return jsonify({"accepted": False, "status": "CONNECTION_ERROR",
                        "message": msg, "timestamp": _now_iso()}), 503

    symbol_info = mt5.symbol_info(symbol)
    if symbol_info is None:
        return jsonify({"accepted": False, "status": "SYMBOL_NOT_FOUND",
                        "message": f"Unknown symbol {symbol}", "timestamp": _now_iso()}), 400
    if not symbol_info.visible:
        mt5.symbol_select(symbol, True)

    tick = mt5.symbol_info_tick(symbol)
    if tick is None:
        return jsonify({"accepted": False, "status": "NO_QUOTE",
                        "message": f"No live quote for {symbol}", "timestamp": _now_iso()}), 503

    pip = _pip_size(symbol_info)
    sl_pips = int(data.get("stopLossPips") or 0)
    tp_pips = int(data.get("takeProfitPips") or 0)
    deviation = int(data.get("maxSlippagePoints") or 20)
    magic = int(data.get("magicNumber") or 0)
    comment = (data.get("comment") or "forexbot")[:31]

    if side == "BUY":
        order_type = mt5.ORDER_TYPE_BUY
        price = tick.ask
        sl = price - sl_pips * pip if sl_pips else 0.0
        tp = price + tp_pips * pip if tp_pips else 0.0
    else:
        order_type = mt5.ORDER_TYPE_SELL
        price = tick.bid
        sl = price + sl_pips * pip if sl_pips else 0.0
        tp = price - tp_pips * pip if tp_pips else 0.0

    request_payload = {
        "action": mt5.TRADE_ACTION_DEAL,
        "symbol": symbol,
        "volume": volume,
        "type": order_type,
        "price": price,
        "sl": round(sl, symbol_info.digits) if sl else 0.0,
        "tp": round(tp, symbol_info.digits) if tp else 0.0,
        "deviation": deviation,
        "magic": magic,
        "comment": comment,
        "type_time": mt5.ORDER_TIME_GTC,
        "type_filling": mt5.ORDER_FILLING_IOC,
    }

    log.info("Sending %s %s %.2f lots @ %.5f (SL=%.5f TP=%.5f)",
             side, symbol, volume, price, sl, tp)
    result = mt5.order_send(request_payload)

    if result is None:
        code, err = mt5.last_error()
        log.error("order_send returned None (%s): %s", code, err)
        return jsonify({"accepted": False, "status": "SEND_FAILED",
                        "brokerRetcode": code, "message": err,
                        "timestamp": _now_iso()}), 502

    accepted = result.retcode == mt5.TRADE_RETCODE_DONE
    slippage_pips = abs(result.price - price) / pip if pip else 0.0

    response = {
        "accepted": accepted,
        "status": "FILLED" if accepted else "REJECTED",
        "brokerRetcode": result.retcode,
        "orderTicket": getattr(result, "order", None),
        "executedPrice": result.price,
        "executedVolume": result.volume,
        "slippagePips": round(slippage_pips, 2),
        "message": result.comment,
        "timestamp": _now_iso(),
    }

    if accepted:
        log.info("FILLED ticket=%s price=%.5f slippage=%.2f pips",
                 response["orderTicket"], result.price, slippage_pips)
    else:
        log.warning("REJECTED retcode=%s comment=%s", result.retcode, result.comment)

    return jsonify(response), (200 if accepted else 422)


if __name__ == "__main__":
    log.info("Starting MT5 bridge on %s:%s", HOST, PORT)
    app.run(host=HOST, port=PORT)


# AI Forex Trading Bot — Streamlit Frontend (JTICKET-07)

A Streamlit UI that connects to the Spring Boot backend
(`http://localhost:8080/api/bot/...`).

## Endpoints used
| UI action | Backend call |
|-----------|--------------|
| Health banner | `GET /api/bot/health` |
| Apply Configuration (sidebar form) | `POST /api/bot/config` |
| Start Bot | `POST /api/bot/start?accountId=...` |
| Stop Bot | `POST /api/bot/stop` |
| Refresh / status panel | `GET /api/bot/status` |
| Send Market Tick (manual test) | `POST /api/bot/tick` |

## Setup
```bash
cd frontend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

## Run
Start the Spring Boot backend first, then:
```bash
streamlit run streamlit_app.py
```

## Configuration
Override the backend URL/timeout with environment variables:
```bash
export FOREXBOT_API_URL="http://localhost:8080/api/bot"
export FOREXBOT_API_TIMEOUT=10
```


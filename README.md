# pFab Demo (Utility POI SLD)

US-focused deployable demo for Utility Point of Interconnection (POI) monitoring and AI load ramp coordination.

## Monorepo layout

- `api/` — Node.js + Express simulation API
- `web/` — React + TypeScript (Vite) UI

## Prerequisites

- Node.js 20+
- npm 10+

## Local development (exact steps)

1. Install dependencies:
   ```bash
   npm install
   ```
2. Set web env var:
   ```bash
   cp web/.env.example web/.env
   ```
3. Start API + Web together:
   ```bash
   npm run dev
   ```
4. Open the demo:
   - UI: `http://localhost:5173`
   - API overview: `http://localhost:4000/api/overview`

## Build

```bash
npm run build
```

Build outputs:
- API bundle copy: `api/dist/index.js`
- Web static assets: `web/dist/`

## Deployable hosting recipe

### Option A: Render (single repo, two services)

1. Push repo to GitHub.
2. Create **Web Service** for API:
   - Root directory: `api`
   - Build command: `npm install`
   - Start command: `npm run start`
3. Create **Static Site** for UI:
   - Root directory: `web`
   - Build command: `npm install && npm run build`
   - Publish directory: `dist`
   - Env var: `VITE_API_BASE_URL=https://<your-api-service>.onrender.com`

### Option B: Vercel (web) + Railway/Fly/Render (api)

- Deploy `api` as Node server.
- Deploy `web` as static Vite app with `VITE_API_BASE_URL` set to deployed API URL.

## API contract

- `GET /api/overview`
- `POST /api/simulate/ramp` payload: `{ "datacenter_id":"dc-1", "delta_mw":12, "duration_min":20 }`
- `POST /api/control/accept` payload: `{ "datacenter_id":"dc-1", "target_mw":88 }`

Simulation ticks every 10 seconds.

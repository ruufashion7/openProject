# Free deployment (Atlas + Render + Vercel)

Stack: **MongoDB Atlas** (DB) → **Render** (Spring Boot API, Docker) → **Vercel** (Angular).  
Costs: Atlas M0, Render free web service, Vercel hobby — all have limits; fine for demos.

**Slow first load / login on prod?** See [PERFORMANCE.md](PERFORMANCE.md) (cold starts, regions, what the app does).

## 1. MongoDB Atlas

1. Create an **M0** cluster.
2. **Database Access**: create a user + password.
3. **Network Access**: add **`0.0.0.0/0`** (or tighten later).
4. **Connect** → copy the **SRV** string, set database path to **`/openProject`** before `?`, e.g.  
   `...mongodb.net/openProject?appName=...`  
5. URL-encode special characters in the password (`@` → `%40`).

## 2. Render (backend)

**Option A — Blueprint (uses `render.yaml` in this repo)**  
1. [dashboard.render.com](https://dashboard.render.com) → **New** → **Blueprint**.  
2. Connect **`ruufashion7/openProject`** (or your fork).  
3. When prompted, set:
   - **`MONGO_URI`** — full Atlas URI  
   - **`ADMIN_USERNAME`** / **`ADMIN_PASSWORD`** — first admin login  
   - **`CORS_ALLOWED_ORIGINS`** — `https://burning-ice.vercel.app` (production frontend; set in `render.yaml`).  
   - **`SECURITY_ENCRYPTION_KEY`** / **`SECURITY_JWT_SECRET`** — auto-generated if you use the Blueprint as written; otherwise set manually in the dashboard. **`SECURITY_ENCRYPTION_KEY`** must be valid Base64 that decodes to **32 bytes** (same as `openssl rand -base64 32`). If the generated value ever fails startup, replace it with that command output.

The Blueprint sets **`SPRING_PROFILES_ACTIVE=prod`** and **`OPENPROJECT_REDIS_ENABLED=false`**: Redis is off by default so health checks pass without Upstash. To enable Redis: create Upstash (TCP **`rediss://`** URL from Connect), set **`OPENPROJECT_REDIS_ENABLED=true`** and **`SPRING_DATA_REDIS_URL`**, then redeploy. Delete any stale **`SPRING_DATA_REDIS_URL`** / **`REDIS_URL`** if deploy logs show an unknown Upstash host.

**Option B — Web Service manually**  
1. **New** → **Web Service** → connect repo.  
2. **Runtime**: **Docker** · **Dockerfile path**: `./Dockerfile` · **Branch**: `main`.  
3. **Instance**: **Free**.  
4. **Health check path**: **`/actuator/health`**.  
5. **Environment** — same variables as in `render.yaml` (at minimum: `MONGO_URI`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `SECURITY_ENCRYPTION_KEY`, `SECURITY_JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, and **`SPRING_PROFILES_ACTIVE=prod`**).  
   Generate secrets: `openssl rand -base64 32` (use two different values for the two keys).

6. **Deploy** and copy the public URL, e.g. `https://openproject-backend.onrender.com`.

7. Test: open **`https://YOUR-SERVICE.onrender.com/actuator/health`** — expect JSON with **UP**.

## 3. Frontend → API (`frontend/vercel.json`)

Vercel rewrites `/api/*` to your Render host. Edit **`frontend/vercel.json`**:

```json
"destination": "https://YOUR-RENDER-SUBDOMAIN.onrender.com/api/:path*"
```

Commit and push:

```bash
git add frontend/vercel.json frontend/src/environments/environment.prod.ts
git commit -m "Point Vercel rewrites to Render backend"
git push origin main
```

**Avoid 502 on first login:** `environment.prod.ts` sets `apiBaseUrl` to your Render host so the browser calls the API directly (Vercel rewrites time out during Render cold start). Keep `CORS_ALLOWED_ORIGINS` in sync with your Vercel URL.

## 4. Vercel (frontend)

1. [vercel.com](https://vercel.com) → **Add New** → **Project** → import the GitHub repo.  
2. **Root Directory**: **`frontend`**.  
3. **Build Command**: `npm run build`.  
4. **Output Directory**: **`dist/frontend/browser`**.  
5. Deploy → production URL: **`https://burning-ice.vercel.app`** (login: `/login`).

6. In Vercel → **Settings → Domains**, set **`burning-ice.vercel.app`** as the production domain. Remove or delete the old project **`open-project-henna`** if it still exists as a separate deployment.

## 5. CORS (required)

In Render → **Environment** → **`CORS_ALLOWED_ORIGINS`**:

`https://burning-ice.vercel.app`

(no trailing slash). This is already in **`render.yaml`** — after push, **Manual Deploy** on Render if the env var was still pointing at an old URL.

Do **not** set `CORS_ALLOWED_ORIGIN_PATTERNS` to `https://*.vercel.app` unless you need preview deploys; it would allow old hostnames to call the API.

## 6. Smoke test

- Open [https://burning-ice.vercel.app/login](https://burning-ice.vercel.app/login) → log in with admin credentials from Render.  
- Old URL `open-project-henna.vercel.app` should redirect to `burning-ice.vercel.app` (same Vercel project). If it is a **separate** Vercel project, delete that project in the Vercel dashboard.  
- If API calls fail: fix **`CORS_ALLOWED_ORIGINS`** and **`frontend/vercel.json`** destination, redeploy.

## 7. Monitoring (optional — Prometheus + Grafana on Render)

The Blueprint (`render.yaml`) deploys two extra free web services:

| Service | URL pattern |
|---------|-------------|
| Prometheus | `https://openproject-prometheus.onrender.com` |
| Grafana | `https://openproject-grafana.onrender.com` |

- **`METRICS_SCRAPE_TOKEN`** is auto-generated on the backend; Prometheus uses the same token to scrape `/actuator/prometheus`.
- Grafana admin password: Render → **openproject-grafana** → Environment → **`GF_SECURITY_ADMIN_PASSWORD`**.
- Dashboard: **openProject — Spring Boot (Micrometer)** (provisioned on first login).

Full guide: [monitoring/README.md](../monitoring/README.md) (Scenario C).

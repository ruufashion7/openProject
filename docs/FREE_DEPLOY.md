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
   - **`CORS_ALLOWED_ORIGINS`** — your Vercel URL (step 4). If Vercel is not ready yet, use `http://localhost:4200` and update later.  
   - **`SECURITY_ENCRYPTION_KEY`** / **`SECURITY_JWT_SECRET`** — auto-generated if you use the Blueprint as written; otherwise set manually in the dashboard. **`SECURITY_ENCRYPTION_KEY`** must be valid Base64 that decodes to **32 bytes** (same as `openssl rand -base64 32`). If the generated value ever fails startup, replace it with that command output.

The Blueprint sets **`SPRING_PROFILES_ACTIVE=prod`**: the API disables Redis unless **`SPRING_DATA_REDIS_URL`** (or `spring.data.redis.url`) is set, so health checks pass without a local Redis. To use managed Redis (e.g. Upstash), add that URL and redeploy.

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
git add frontend/vercel.json
git commit -m "Point Vercel rewrites to Render backend"
git push origin main
```

## 4. Vercel (frontend)

1. [vercel.com](https://vercel.com) → **Add New** → **Project** → import the GitHub repo.  
2. **Root Directory**: **`frontend`**.  
3. **Build Command**: `npm run build`.  
4. **Output Directory**: **`dist/frontend/browser`**.  
5. Deploy → copy the site URL, e.g. `https://open-project.vercel.app`.

## 5. CORS (required)

In Render → **Environment** → **`CORS_ALLOWED_ORIGINS`** = **exact** Vercel origin (no trailing slash unless you know you need it), e.g.:

`https://your-app.vercel.app`

Save → **Manual Deploy** on Render.

## 6. Smoke test

- Open the Vercel URL → log in with admin credentials from Render.  
- If API calls fail: fix **`CORS_ALLOWED_ORIGINS`** and **`frontend/vercel.json`** destination, redeploy.

# OpenProject

Angular frontend + Spring Boot backend with a simple dashboard API.

## Backend (Spring Boot)
From the repo root:

```
mvn spring-boot:run
```

**MongoDB (local vs Atlas):** By default the app uses `mongodb://localhost:27017/openProject` (start Mongo with `docker compose up -d mongo`, or use full Docker stack). To use **the same Atlas cluster as MongoDB Compass** on your machine, copy [`.env.local.properties.example`](.env.local.properties.example) to **`.env.local.properties`** in the repo root and set `mongo_uri` to your Compass connection string (that file is gitignored). Alternatively set the **`MONGO_URI`** environment variable in your IDE or shell. Allow your IP in Atlas **Network Access** when using Atlas. See [docs/MONGODB_ATLAS.md](docs/MONGODB_ATLAS.md). Optional: `./scripts/run-backend-atlas.sh` (TLS JVM flags).

API endpoints:
- `GET http://localhost:8080/api/health`
- `GET http://localhost:8080/api/dashboard`

## Frontend (Angular)
From the repo root:

```
cd frontend
npm start
```

The Angular dev server runs on `http://localhost:4200` and proxies `/api` to the backend via `proxy.conf.json`.

## Docker (MongoDB + Backend + Frontend)
From the repo root:

```
docker compose up --build
```

This starts:
- MongoDB on `mongodb://localhost:27017/openProject`
- Backend on `http://localhost:8080`
- Frontend on `http://localhost:4200`

## Free deployment (Vercel + Render + MongoDB Atlas)

The app can be deployed for free so the world can use it:

1. **Push to GitHub**: Create a new repo on GitHub, then run:
   ```bash
   git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
   git push -u origin main
   ```

2. **MongoDB Atlas**: Create a free M0 cluster at [mongodb.com/atlas](https://www.mongodb.com/atlas), add a DB user and allow network access `0.0.0.0/0`, and copy the connection string.

3. **Backend on Render**: At [render.com](https://render.com), New > Web Service, connect your repo. Set Runtime to **Docker**, Instance Type **Free**. Add env vars: `MONGO_URI`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `SECURITY_ENCRYPTION_KEY` (e.g. `openssl rand -base64 32`), and `CORS_ALLOWED_ORIGINS` = your Vercel app URL (e.g. `https://your-app.vercel.app`). Set **Health Check Path** to `/actuator/health`. Deploy and note the backend URL.

4. **Frontend on Vercel**: At [vercel.com](https://vercel.com), import your repo. Set **Root Directory** to `frontend`, **Build Command** to `npm run build`, **Output Directory** to `dist/frontend/browser`. In `frontend/vercel.json`, set the rewrite `destination` to your Render backend URL (e.g. `https://openproject-backend.onrender.com`). Deploy.

5. **CORS**: Ensure Render has `CORS_ALLOWED_ORIGINS` set to your Vercel URL (e.g. `https://your-project.vercel.app`).

**Full checklist** (Blueprint + Vercel settings): [docs/FREE_DEPLOY.md](docs/FREE_DEPLOY.md). This repo includes **`render.yaml`** for Render’s Blueprint flow.

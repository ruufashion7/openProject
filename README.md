# OpenProject

Angular frontend + Spring Boot backend with a simple dashboard API.

## Backend (Spring Boot)
From the repo root:

```
mvn spring-boot:run
```

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


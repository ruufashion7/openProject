# MongoDB Atlas + Compass

The backend uses **MongoDB Atlas** over TLS. The app no longer uses any corporate MongoDB hosts.

## 1. Atlas (you must click these in the browser)

1. Log in at [cloud.mongodb.com](https://cloud.mongodb.com).
2. **Network Access** → **Add IP Address** → **Allow access from anywhere** (`0.0.0.0/0`) → Confirm.  
   *Without this, Compass and Render often fail with TLS/internal errors.*
3. **Database** → **Connect** → choose **Compass** or **Drivers** and copy the URI.

## 2. Compass connection string

If your password contains `@`, replace each `@` with `%40` in the URI:

```text
mongodb+srv://USER:PASSWORD_WITH_%40_FOR_@_CHARS@cluster0.xxxxx.mongodb.net/DATABASE
```

Example database name used by this project: `openProject`.

## 3. Backend env var (production)

Set:

```bash
export MONGO_URI='mongodb+srv://USER:PASSWORD_ENCODED@cluster0.xxxxx.mongodb.net/openProject'
```

## 4. Verify the Java app

```bash
./scripts/run-backend-atlas.sh
```

Or:

```bash
mvn spring-boot:run
```

On startup, logs should show MongoDB client creation **without** TLS/auth errors. Use the app (login, etc.), then refresh **Compass** — you should see collections under database `openProject`.

## 5. If Compass still shows SSL alert 80

- Wait 2 minutes after changing Network Access.
- Turn off **VPN**; try **phone hotspot**.
- Update **Compass** to the latest version.

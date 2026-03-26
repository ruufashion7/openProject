# Production performance

## 1. Confirm Render cold start vs warm service

Free Render web services **spin down** after idle time. The **first request** after sleep pays **JVM + Spring Boot startup** (often tens of seconds).

**How to verify (Chrome DevTools):**

1. Open your prod site. Open **DevTools** → **Network**.
2. After the app has been **idle ~15+ minutes**, hard-refresh or open **login** again.
3. Select the first **`/api/...`** or **`/actuator/health`** request.
4. Check **Timing** → **Waiting for server response** (TTFB). If it is **very large** (e.g. 30s+) on the first hit but **small** on immediate repeats, the bottleneck is **cold start**, not application logic.

**Mitigations:** Paid Render instance (always on), or accept first-hit delay on free tier.

## 2. Align regions (latency)

Each hop adds round-trip time: **browser → CDN (Vercel) → API (Render) → MongoDB Atlas → optional Redis (e.g. Upstash)**.

**Guidelines:**

- Put **MongoDB Atlas** in the **same region** as your **Render** service when possible (see [`render.yaml`](../render.yaml) `region`, e.g. `oregon` → Atlas **AWS Oregon**).
- Put **Upstash** (or any Redis) in a region **close to Render**, not close only to your laptop.
- **Vercel** serves static assets from the edge; API calls still go to **Render’s region**, so API/DB colocation matters most.

## 3. Application-side behavior (this repo)

- **Login:** Optional Mongo session mirror runs **asynchronously** after successful login so the HTTP response returns slightly faster; the **Admin → Sessions** list may show the new session **within a second or two** of login.
- **Angular:** Authenticated routes use **lazy-loaded** chunks so the **initial download for `/login`** is smaller.
- **BCrypt:** Strength is configurable via `security.password.bcrypt-strength` (default `12`). Lower values speed up login slightly but **weaken** offline guessing resistance—change only with care.

## 4. Further reading

- Full deploy checklist: [FREE_DEPLOY.md](FREE_DEPLOY.md)

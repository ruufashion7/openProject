import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { normalizePermissions } from './permissions.config';

export interface UserPermissions {
  fileUpload: boolean;
  hardDelete: boolean;
  invoicePage: boolean;
  detailsPage: boolean;
  wholeProjectDownload: boolean;
  outstandingPage: boolean;
  paymentDateEdit: boolean;
  whatsappDateChange: boolean;
  followUpChange: boolean;
  rateListPage: boolean;
  rateListUpload: boolean;
  salesVisualization: boolean;
  customerLocations: boolean;
  customerCategoryEdit: boolean;
  customerNotesEdit: boolean;
  customerLocationEdit: boolean;
}

interface SessionData {
  token: string;
  /** When true, JWT is in HttpOnly cookie only; do not send Authorization header. */
  useCookieAuth?: boolean;
  displayName: string;
  expiresAt: number;
  userId?: string;
  isAdmin?: boolean;
  permissions?: UserPermissions;
}

interface LoginResponse {
  token: string;
  displayName: string;
  /** ISO string, epoch seconds, epoch ms, or Instant tuple from Jackson */
  expiresAt: string | number | { epochSecond?: number; nano?: number };
  userId?: string;
  isAdmin?: boolean;
  permissions?: UserPermissions;
  csrfToken?: string | null;
}

interface SessionResponse {
  displayName: string;
  expiresAt: string | number | { epochSecond?: number; nano?: number };
  userId?: string;
  isAdmin?: boolean;
  permissions?: UserPermissions;
}

const CSRF_STORAGE_KEY = 'openProject.csrf';

/** RFC 7617 Basic credentials in header — avoids putting password in JSON request body. */
function basicAuthorizationHeader(username: string, password: string): string {
  const pair = `${username}:${password}`;
  const bytes = new TextEncoder().encode(pair);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return `Basic ${btoa(binary)}`;
}

function sanitizeBearerToken(raw: string | undefined | null): string | null {
  if (raw == null || typeof raw !== 'string') {
    return null;
  }
  let t = raw.trim();
  while (t.startsWith('Bearer ')) {
    t = t.slice(7).trim();
  }
  return t.length > 0 ? t : null;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly sessionKey = 'openProject.session';

  constructor(private http: HttpClient) {}

  login(inputUsername: string, inputPassword: string, captchaToken?: string | null): Observable<boolean> {
    let headers = new HttpHeaders({
      Authorization: basicAuthorizationHeader(inputUsername, inputPassword)
    });
    if (captchaToken) {
      headers = headers.set('X-Captcha-Token', captchaToken);
    }
    return this.http
      .post<LoginResponse>('/api/login', null, {
        headers,
        ...(environment.useJwtHttpOnlyCookie ? { withCredentials: true } : {})
      })
      .pipe(
        tap((response) => {
          const useCookie = environment.useJwtHttpOnlyCookie;
          const token = sanitizeBearerToken(response.token);
          if (!useCookie && !token) {
            throw new Error('Login response missing token');
          }
          const expiresAt = this.parseExpiresFromApi(response.expiresAt);
          const session: SessionData = {
            token: useCookie ? '' : token!,
            useCookieAuth: useCookie,
            displayName: response.displayName,
            expiresAt,
            userId: response.userId,
            isAdmin: response.isAdmin || false,
            permissions: normalizePermissions(response.permissions)
          };
          localStorage.setItem(this.sessionKey, JSON.stringify(session));
          if (response.csrfToken) {
            sessionStorage.setItem(CSRF_STORAGE_KEY, response.csrfToken);
          } else {
            sessionStorage.removeItem(CSRF_STORAGE_KEY);
          }
        }),
        map(() => true),
        catchError((err: HttpErrorResponse) => {
          // Re-throw so UI can show the right message (rate limit / server down — not "wrong password")
          if (err.status === 429 || err.status === 0 || err.status >= 500) {
            return throwError(() => err);
          }
          // 401, 400, etc. → failed login, not a server outage
          return of(false);
        })
      );
  }

  logout(): void {
    const token = this.getToken();
    if (token || environment.useJwtHttpOnlyCookie) {
      this.http
        .post('/api/logout', {}, {
          headers: token ? this.buildAuthHeaders(token) : new HttpHeaders(),
          ...(environment.useJwtHttpOnlyCookie ? { withCredentials: true } : {})
        })
        .pipe(catchError(() => of(null)))
        .subscribe();
    }
    localStorage.removeItem(this.sessionKey);
    sessionStorage.removeItem(CSRF_STORAGE_KEY);
  }

  validateSession(): Observable<boolean> {
    const session = this.getSession();
    if (!session) {
      return of(false);
    }
    // Expiry and finite checks are done in getSession() after normalization

    // Only validate with backend if session is close to expiring (within 5 minutes)
    const remainingMs = session.expiresAt - Date.now();
    const fiveMinutes = 5 * 60 * 1000;

    if (!Number.isFinite(remainingMs)) {
      return of(true);
    }

    if (remainingMs > fiveMinutes) {
      return of(true);
    }

    return this.fetchAndMergeSession(session);
  }

  /**
   * Always hits GET /api/session. Used when an API returns 401 so we re-check the token
   * (validateSession() skips the server when expiry looks far away, which hid invalid JWTs).
   */
  verifySessionWithServer(): Observable<boolean> {
    const session = this.getSession();
    if (!session) {
      return of(false);
    }
    return this.fetchAndMergeSession(session);
  }

  /**
   * Loads current permissions from the server and updates the stored session.
   * Call when entering pages that depend on fine-grained flags so UI matches Access Control without re-login.
   */
  refreshSessionPermissionsFromServer(): Observable<boolean> {
    const session = this.getSession();
    if (!session) {
      return of(false);
    }
    return this.http
      .get<SessionResponse>('/api/session', this.apiSessionHttpOptions())
      .pipe(
        tap((response) => {
          const newExpiry = this.parseExpiresFromApi(response.expiresAt);
          const updated: SessionData = {
            ...session,
            token: session.token,
            useCookieAuth: session.useCookieAuth,
            displayName: response.displayName,
            expiresAt: newExpiry,
            userId: response.userId || session.userId,
            isAdmin: response.isAdmin !== undefined ? response.isAdmin : session.isAdmin,
            permissions: normalizePermissions(response.permissions)
          };
          localStorage.setItem(this.sessionKey, JSON.stringify(updated));
        }),
        map(() => true),
        catchError((err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.logout();
            return of(false);
          }
          return of(true);
        })
      );
  }

  private apiSessionHttpOptions(): { headers: HttpHeaders; withCredentials?: boolean } {
    const withCreds = environment.useJwtHttpOnlyCookie;
    return {
      headers: this.getAuthHeaders(),
      ...(withCreds ? { withCredentials: true } : {})
    };
  }

  private fetchAndMergeSession(session: SessionData): Observable<boolean> {
    return this.http
      .get<SessionResponse>('/api/session', this.apiSessionHttpOptions())
      .pipe(
        tap((response) => {
          const newExpiry = this.parseExpiresFromApi(response.expiresAt);
          const mergedPerms = normalizePermissions(response.permissions);
          if (newExpiry > session.expiresAt) {
            const updated: SessionData = {
              ...session,
              token: session.token,
              useCookieAuth: session.useCookieAuth,
              displayName: response.displayName,
              expiresAt: newExpiry,
              userId: response.userId || session.userId,
              isAdmin: response.isAdmin !== undefined ? response.isAdmin : session.isAdmin,
              permissions: mergedPerms
            };
            localStorage.setItem(this.sessionKey, JSON.stringify(updated));
          } else if (response.permissions) {
            const updated: SessionData = {
              ...session,
              permissions: mergedPerms,
              isAdmin: response.isAdmin !== undefined ? response.isAdmin : session.isAdmin,
              userId: response.userId || session.userId
            };
            localStorage.setItem(this.sessionKey, JSON.stringify(updated));
          }
        }),
        map(() => true),
        catchError((err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.logout();
            return of(false);
          }
          // CORS, network down, 5xx: do not clear a locally valid session
          return of(true);
        })
      );
  }

  getDisplayName(): string | null {
    const session = this.getSession();
    return session ? session.displayName : null;
  }

  getSessionExpiresAt(): number | null {
    const session = this.getSession();
    return session ? session.expiresAt : null;
  }

  getRemainingMs(): number | null {
    const expiresAt = this.getSessionExpiresAt();
    if (!expiresAt) {
      return null;
    }
    return expiresAt - Date.now();
  }

  getAuthHeaders(): HttpHeaders {
    const token = this.getToken();
    return token ? this.buildAuthHeaders(token) : new HttpHeaders();
  }

  getToken(): string | null {
    const session = this.getSession();
    if (!session) {
      return null;
    }
    if (session.useCookieAuth) {
      return null;
    }
    return session.token && session.token.length > 0 ? session.token : null;
  }

  getSession(): SessionData | null {
    const raw = localStorage.getItem(this.sessionKey);
    if (!raw) {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as SessionData & { expiresAt?: unknown };
      const useCookieAuth = parsed.useCookieAuth === true;
      const token = sanitizeBearerToken(parsed.token);
      if (!parsed.displayName) {
        localStorage.removeItem(this.sessionKey);
        return null;
      }
      if (!useCookieAuth && !token) {
        localStorage.removeItem(this.sessionKey);
        return null;
      }
      // Normalize any legacy shape (ISO string, epoch seconds, Instant object). Raw ISO strings
      // made Number.isFinite(session.expiresAt) false → immediate logout() on every guard.
      const expiresAt = this.parseExpiresFromApi(parsed.expiresAt as string | number | { epochSecond?: number; nano?: number } | undefined);
      if (!Number.isFinite(expiresAt)) {
        localStorage.removeItem(this.sessionKey);
        return null;
      }
      const session: SessionData = {
        token: useCookieAuth ? '' : token!,
        useCookieAuth,
        displayName: parsed.displayName,
        expiresAt,
        userId: parsed.userId,
        isAdmin: parsed.isAdmin,
        permissions: parsed.permissions ? normalizePermissions(parsed.permissions) : undefined
      };
      if (parsed.expiresAt !== expiresAt) {
        localStorage.setItem(this.sessionKey, JSON.stringify(session));
      }
      if (Date.now() > expiresAt) {
        this.logout();
        return null;
      }
      return session;
    } catch {
      localStorage.removeItem(this.sessionKey);
      return null;
    }
  }

  updateSessionExpiry(expiresAt: number): void {
    // Directly access localStorage to avoid expiry check that might logout
    const raw = localStorage.getItem(this.sessionKey);
    if (!raw) {
      return;
    }
    try {
      const session = JSON.parse(raw) as SessionData;
      const updated: SessionData = {
        ...session,
        expiresAt
      };
      localStorage.setItem(this.sessionKey, JSON.stringify(updated));
    } catch {
      // Invalid session data, ignore
    }
  }

  isAdmin(): boolean {
    const session = this.getSession();
    return session?.isAdmin || false;
  }

  getUserId(): string | null {
    const session = this.getSession();
    return session?.userId || null;
  }

  getPermissions(): UserPermissions | null {
    const session = this.getSession();
    return session?.permissions || null;
  }

  hasPermission(permission: keyof UserPermissions): boolean {
    if (this.isAdmin()) {
      return true; // Admin has all permissions
    }
    const permissions = this.getPermissions();
    return permissions ? permissions[permission] === true : false;
  }

  private buildAuthHeaders(token: string): HttpHeaders {
    return new HttpHeaders({
      Authorization: `Bearer ${token}`
    });
  }

  /**
   * Jackson may send Instant as ISO string, epoch seconds (~1e9) or ms (~1e12+) as number,
   * or { epochSecond, nano }. Treating seconds as ms makes expiry 1970 → immediate logout().
   */
  private parseExpiresFromApi(value: string | number | { epochSecond?: number; nano?: number } | undefined): number {
    if (value === undefined || value === null) {
      return Date.now() + 45 * 60 * 1000;
    }
    if (typeof value === 'number' && Number.isFinite(value)) {
      return value < 1e12 ? value * 1000 : value;
    }
    if (typeof value === 'object' && 'epochSecond' in value && typeof value.epochSecond === 'number') {
      const nano = typeof value.nano === 'number' ? value.nano : 0;
      return value.epochSecond * 1000 + nano / 1e6;
    }
    if (typeof value === 'string') {
      const trimmed = value.trim();
      if (/^\d+$/.test(trimmed)) {
        const n = Number(trimmed);
        return n < 1e12 ? n * 1000 : n;
      }
      const t = new Date(trimmed).getTime();
      if (Number.isFinite(t)) {
        return t;
      }
    }
    return Date.now() + 45 * 60 * 1000;
  }
}


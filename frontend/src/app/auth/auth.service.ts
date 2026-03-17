import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';

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
  salesVisualization: boolean;
  customerLocations: boolean;
}

interface SessionData {
  token: string;
  displayName: string;
  expiresAt: number;
  userId?: string;
  isAdmin?: boolean;
  permissions?: UserPermissions;
}

interface LoginResponse {
  token: string;
  displayName: string;
  expiresAt: string;
  userId?: string;
  isAdmin?: boolean;
  permissions?: UserPermissions;
}

interface SessionResponse {
  displayName: string;
  expiresAt: string;
  userId?: string;
  isAdmin?: boolean;
  permissions?: UserPermissions;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly sessionKey = 'openProject.session';

  constructor(private http: HttpClient) {}

  login(inputUsername: string, inputPassword: string): Observable<boolean> {
    return this.http
      .post<LoginResponse>('/api/login', {
        username: inputUsername,
        password: inputPassword
      })
      .pipe(
        tap((response) => {
          const session: SessionData = {
            token: response.token,
            displayName: response.displayName,
            expiresAt: new Date(response.expiresAt).getTime(),
            userId: response.userId,
            isAdmin: response.isAdmin || false,
            permissions: response.permissions
          };
          localStorage.setItem(this.sessionKey, JSON.stringify(session));
        }),
        map(() => true),
        catchError(() => of(false))
      );
  }

  logout(): void {
    const token = this.getToken();
    if (token) {
      this.http
        .post('/api/logout', {}, { headers: this.buildAuthHeaders(token) })
        .pipe(catchError(() => of(null)))
        .subscribe();
    }
    localStorage.removeItem(this.sessionKey);
  }

  validateSession(): Observable<boolean> {
    const session = this.getSession();
    if (!session) {
      return of(false);
    }
    if (Date.now() > session.expiresAt) {
      this.logout();
      return of(false);
    }

    // Only validate with backend if session is close to expiring (within 5 minutes)
    // This prevents resetting the session timer on every page navigation
    const remainingMs = session.expiresAt - Date.now();
    const fiveMinutes = 5 * 60 * 1000;
    
    if (remainingMs > fiveMinutes) {
      // Session is still valid and not close to expiring, no need to call backend
      return of(true);
    }

    // Session is close to expiring, validate with backend to potentially extend it
    return this.http
      .get<SessionResponse>('/api/session', { headers: this.buildAuthHeaders(session.token) })
      .pipe(
        tap((response) => {
          const newExpiry = new Date(response.expiresAt).getTime();
          // Only update if the new expiry is actually later (session was extended)
          // This prevents resetting the timer if backend hasn't actually extended it
          if (newExpiry > session.expiresAt) {
            const updated: SessionData = {
              token: session.token,
              displayName: response.displayName,
              expiresAt: newExpiry,
              userId: response.userId || session.userId,
              isAdmin: response.isAdmin !== undefined ? response.isAdmin : session.isAdmin,
              permissions: response.permissions || session.permissions
            };
            localStorage.setItem(this.sessionKey, JSON.stringify(updated));
          } else if (response.permissions) {
            // Update permissions even if expiry hasn't changed
            const updated: SessionData = {
              ...session,
              permissions: response.permissions,
              isAdmin: response.isAdmin !== undefined ? response.isAdmin : session.isAdmin,
              userId: response.userId || session.userId
            };
            localStorage.setItem(this.sessionKey, JSON.stringify(updated));
          }
        }),
        map(() => true),
        catchError(() => {
          this.logout();
          return of(false);
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
    return session ? session.token : null;
  }

  getSession(): SessionData | null {
    const raw = localStorage.getItem(this.sessionKey);
    if (!raw) {
      return null;
    }
    try {
      const session = JSON.parse(raw) as SessionData;
      
      // SECURITY: Validate session data structure
      if (!session.token || !session.displayName || !session.expiresAt) {
        localStorage.removeItem(this.sessionKey);
        return null;
      }
      
      // Check if session is expired
      if (Date.now() > session.expiresAt) {
        this.logout();
        return null;
      }
      
      return session;
    } catch {
      // SECURITY: Invalid session data, remove it
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
        token: session.token,
        displayName: session.displayName,
        expiresAt: expiresAt
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
}


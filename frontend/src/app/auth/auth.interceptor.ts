import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, tap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

function pathOnly(url: string): string {
  const q = url.indexOf('?');
  return q >= 0 ? url.slice(0, q) : url;
}

/** POST /api/login only — avoid matching e.g. /api/loginAttempts */
function isPostToLogin(req: { method: string; url: string }): boolean {
  if (req.method !== 'POST') {
    return false;
  }
  const p = pathOnly(req.url);
  return p.endsWith('/api/login') || p.endsWith('/api/login/');
}

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  // Do not attach auth or error handling to login POST (pass response through unchanged)
  if (isPostToLogin(req)) {
    return next(req);
  }

  // Add auth header to all API requests
  const token = auth.getToken();
  if (token) {
    req = req.clone({
      setHeaders: {
        Authorization: `Bearer ${token}`
      }
    });
  }

  // Handle response
  return next(req).pipe(
    tap((response: any) => {
      // Don't automatically update session expiry on API calls
      // Session expiry is only updated when explicitly validated via validateSession()
      // This prevents resetting the session timer on every page change or API call
    }),
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        const failedPath = pathOnly(error.url ?? req.url);
        // Invalid credentials on login, or logout with bad token — never run session recovery
        if (
          failedPath.endsWith('/api/login') ||
          failedPath.endsWith('/api/login/') ||
          failedPath.endsWith('/api/logout') ||
          failedPath.endsWith('/api/logout/')
        ) {
          return throwError(() => error);
        }
        const url = error.url ?? req.url;
        if (url.includes('/api/session')) {
          auth.logout();
          router.navigateByUrl('/login');
          return throwError(() => error);
        }
        const session = auth.getSession();
        if (session) {
          auth.verifySessionWithServer().subscribe({
            next: (valid) => {
              if (!valid) {
                auth.logout();
                router.navigateByUrl('/login');
              }
            }
          });
        } else {
          auth.logout();
          const route = router.url.split('?')[0];
          if (route !== '/login' && route !== '/') {
            router.navigateByUrl('/login');
          }
        }
      }
      
      // SECURITY: Handle rate limiting (429 Too Many Requests)
      // Rate limit exceeded - silently handled, error returned to caller
      
      // SECURITY: Handle forbidden access (403)
      // Access forbidden - silently handled, error returned to caller
      
      return throwError(() => error);
    })
  );
};


import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, tap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  // Skip interceptor for login endpoint
  if (req.url.includes('/api/login')) {
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
      // Handle 401 Unauthorized
      if (error.status === 401) {
        // Try to refresh session
        const session = auth.getSession();
        if (session) {
          // Attempt to validate/refresh session
          auth.validateSession().subscribe({
            next: (valid) => {
              if (!valid) {
                // Session refresh failed, logout and redirect
                auth.logout();
                router.navigateByUrl('/login');
              }
              // If valid, the request will need to be retried by the caller
            },
            error: () => {
              // Session validation failed, logout and redirect
              auth.logout();
              router.navigateByUrl('/login');
            }
          });
        } else {
          // No session, redirect to login
          auth.logout();
          router.navigateByUrl('/login');
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


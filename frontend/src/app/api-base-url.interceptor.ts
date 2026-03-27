import { HttpInterceptorFn } from '@angular/common/http';
import { environment } from '../environments/environment';

/**
 * When {@link environment.apiBaseUrl} is set (e.g. https://api.example.com), prepends it to relative `/api/...` URLs
 * so the SPA can call the API directly instead of relying on a host rewrite (Vercel → upstream), avoiding short proxy timeouts during cold starts.
 */
export const apiBaseUrlInterceptor: HttpInterceptorFn = (req, next) => {
  const base = environment.apiBaseUrl?.trim() ?? '';
  if (!base || req.url.startsWith('http://') || req.url.startsWith('https://')) {
    return next(req);
  }
  if (req.url.startsWith('/api')) {
    const root = base.endsWith('/') ? base.slice(0, -1) : base;
    return next(req.clone({ url: root + req.url }));
  }
  return next(req);
};

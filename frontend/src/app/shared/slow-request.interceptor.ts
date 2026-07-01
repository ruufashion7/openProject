import { HttpInterceptorFn, HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, tap, finalize } from 'rxjs';
import { NotificationService } from './notification.service';

const SLOW_MS = 12_000;
let slowNoticeShown = false;

function pathOnly(url: string): string {
  const q = url.indexOf('?');
  return q >= 0 ? url.slice(0, q) : url;
}

export const slowRequestInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn
): Observable<HttpEvent<unknown>> => {
  const notifications = inject(NotificationService);
  const p = pathOnly(req.url);
  if (!p.includes('/api/')) {
    return next(req);
  }
  // Large multipart uploads routinely exceed 12s — do not show cold-start toast.
  if (req.method === 'POST' && (p.endsWith('/api/upload') || p.endsWith('/api/upload/'))) {
    return next(req);
  }

  const started = Date.now();
  let notified = false;

  return next(req).pipe(
    tap({
      next: () => {
        const elapsed = Date.now() - started;
        if (elapsed >= SLOW_MS && !slowNoticeShown && !notified) {
          slowNoticeShown = true;
          notified = true;
          notifications.showInfo(
            'Server is taking longer than usual (cold start on free hosting). Please wait…',
            8000
          );
        }
      },
      error: () => {
        const elapsed = Date.now() - started;
        if (elapsed >= SLOW_MS && !slowNoticeShown && !notified) {
          slowNoticeShown = true;
          notified = true;
          notifications.showInfo(
            'Server is taking longer than usual (cold start on free hosting). Please wait…',
            8000
          );
        }
      }
    }),
    finalize(() => {
      if (Date.now() - started < SLOW_MS) {
        slowNoticeShown = false;
      }
    })
  );
};

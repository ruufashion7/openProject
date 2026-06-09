import { HttpErrorResponse } from '@angular/common/http';

/** User-facing message from an HTTP error (shared across feature pages). */
export function messageFromHttpError(err: HttpErrorResponse, fallback: string): string {
  if (err.status === 0) {
    return 'Unable to reach the server. It may be waking up — wait a moment and try again.';
  }
  if (err.status === 429) {
    return 'Too many requests. Please wait and try again.';
  }
  if (err.status === 503 || err.status === 502 || err.status === 504) {
    return 'Server is temporarily unavailable. Please try again in a moment.';
  }
  const body = err.error;
  if (typeof body === 'object' && body !== null) {
    const msg = (body as { message?: string; error?: string }).message ?? (body as { error?: string }).error;
    if (typeof msg === 'string' && msg.trim()) {
      return msg;
    }
  }
  if (typeof body === 'string' && body.trim()) {
    return body;
  }
  return fallback;
}

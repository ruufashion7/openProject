import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map, shareReplay } from 'rxjs/operators';
import { environment } from '../../environments/environment';

/**
 * Pings Render `/actuator/health` once per session so the JVM can start before login.
 * Only runs in production when {@link environment.apiBaseUrl} is set.
 */
@Injectable({ providedIn: 'root' })
export class BackendWarmupService {
  private warmup$?: Observable<boolean>;

  constructor(private http: HttpClient) {}

  warmUp(): Observable<boolean> {
    const base = environment.apiBaseUrl?.trim() ?? '';
    if (!environment.production || !base) {
      return of(true);
    }
    if (!this.warmup$) {
      const root = base.endsWith('/') ? base.slice(0, -1) : base;
      this.warmup$ = this.http.get(`${root}/actuator/health`, { responseType: 'text' }).pipe(
        map(() => true),
        catchError(() => of(false)),
        shareReplay(1)
      );
    }
    return this.warmup$;
  }
}

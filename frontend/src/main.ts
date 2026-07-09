import { bootstrapApplication } from '@angular/platform-browser';
import { inject } from '@vercel/analytics';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';
import { environment } from './environments/environment';

const CHUNK_RELOAD_KEY = 'openProject.chunkReloadAttempted';

/** After a deploy, cached route chunks may 404; reload once so the browser picks up new hashes. */
function registerLazyChunkReload(): void {
  window.addEventListener('unhandledrejection', (event: PromiseRejectionEvent) => {
    const message = String(event.reason?.message ?? event.reason ?? '');
    const isStaleChunk =
      message.includes('Failed to fetch dynamically imported module') ||
      message.includes('Importing a module script failed') ||
      message.includes('error loading dynamically imported module');

    if (!isStaleChunk) {
      return;
    }

    if (sessionStorage.getItem(CHUNK_RELOAD_KEY)) {
      sessionStorage.removeItem(CHUNK_RELOAD_KEY);
      return;
    }

    sessionStorage.setItem(CHUNK_RELOAD_KEY, '1');
    window.location.reload();
  });
}

registerLazyChunkReload();

inject({ mode: environment.production ? 'production' : 'development' });

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));

import { bootstrapApplication } from '@angular/platform-browser';
import { inject } from '@vercel/analytics';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';
import { environment } from './environments/environment';

inject({ mode: environment.production ? 'production' : 'development' });

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));

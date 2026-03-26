import { ApplicationConfig, importProvidersFrom } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { NgChartsModule } from 'ng2-charts';

import { routes } from './app.routes';
import { apiBaseUrlInterceptor } from './api-base-url.interceptor';
import { authInterceptor } from './auth/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(withInterceptors([apiBaseUrlInterceptor, authInterceptor])),
    provideRouter(routes),
    importProvidersFrom(NgChartsModule)
  ]
};

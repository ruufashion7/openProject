import { Routes } from '@angular/router';
import { LoginComponent } from './login/login.component';
import { authGuard } from './auth/auth.guard';
import { adminGuard } from './auth/admin.guard';

/** Login stays eager so first paint to /login avoids an extra chunk. Other routes are lazy-loaded to shrink the initial bundle. */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'login'
  },
  {
    path: 'login',
    component: LoginComponent
  },
  {
    path: 'welcome',
    loadComponent: () => import('./welcome/welcome.component').then((m) => m.WelcomeComponent),
    canActivate: [authGuard]
  },
  {
    path: 'upload',
    loadComponent: () => import('./upload/upload.component').then((m) => m.UploadComponent),
    canActivate: [authGuard]
  },
  {
    path: 'rate-list',
    loadComponent: () => import('./rate-list/rate-list.component').then((m) => m.RateListComponent),
    canActivate: [authGuard]
  },
  {
    path: 'uploads',
    loadComponent: () => import('./uploads/uploads.component').then((m) => m.UploadsComponent),
    canActivate: [authGuard]
  },
  {
    path: 'uploads-audit',
    loadComponent: () =>
      import('./uploads-audit/uploads-audit.component').then((m) => m.UploadsAuditComponent),
    canActivate: [authGuard]
  },
  {
    path: 'uploads-purge',
    loadComponent: () =>
      import('./uploads-purge/uploads-purge.component').then((m) => m.UploadsPurgeComponent),
    canActivate: [authGuard]
  },
  {
    path: 'outstanding',
    loadComponent: () => import('./outstanding/outstanding.component').then((m) => m.OutstandingComponent),
    canActivate: [authGuard]
  },
  {
    path: 'payment-dates',
    loadComponent: () =>
      import('./payment-dates/payment-dates.component').then((m) => m.PaymentDatesComponent),
    canActivate: [authGuard]
  },
  {
    path: 'sales-details',
    loadComponent: () =>
      import('./sales-details/sales-details.component').then((m) => m.SalesDetailsComponent),
    canActivate: [authGuard]
  },
  {
    path: 'sales-visualization',
    loadComponent: () =>
      import('./sales-visualization/sales-visualization.component').then((m) => m.SalesVisualizationComponent),
    canActivate: [authGuard]
  },
  {
    path: 'customer-locations',
    loadComponent: () =>
      import('./customer-locations/customer-locations.component').then((m) => m.CustomerLocationsComponent),
    canActivate: [authGuard]
  },
  {
    path: 'access-control',
    loadComponent: () =>
      import('./access-control/access-control.component').then((m) => m.AccessControlComponent),
    canActivate: [adminGuard]
  },
  {
    path: 'sessions',
    loadComponent: () => import('./sessions/sessions.component').then((m) => m.SessionsComponent),
    canActivate: [adminGuard]
  },
  {
    path: '**',
    redirectTo: 'login'
  }
];

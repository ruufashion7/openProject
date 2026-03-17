import { Routes } from '@angular/router';
import { LoginComponent } from './login/login.component';
import { WelcomeComponent } from './welcome/welcome.component';
import { UploadComponent } from './upload/upload.component';
import { UploadsComponent } from './uploads/uploads.component';
import { UploadsAuditComponent } from './uploads-audit/uploads-audit.component';
import { UploadsPurgeComponent } from './uploads-purge/uploads-purge.component';
import { OutstandingComponent } from './outstanding/outstanding.component';
import { PaymentDatesComponent } from './payment-dates/payment-dates.component';
import { SalesDetailsComponent } from './sales-details/sales-details.component';
import { SalesVisualizationComponent } from './sales-visualization/sales-visualization.component';
import { RateListComponent } from './rate-list/rate-list.component';
import { AccessControlComponent } from './access-control/access-control.component';
import { SessionsComponent } from './sessions/sessions.component';
import { CustomerLocationsComponent } from './customer-locations/customer-locations.component';
import { authGuard } from './auth/auth.guard';
import { adminGuard } from './auth/admin.guard';

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
    component: WelcomeComponent,
    canActivate: [authGuard]
  },
  {
    path: 'upload',
    component: UploadComponent,
    canActivate: [authGuard]
  },
  {
    path: 'rate-list',
    component: RateListComponent,
    canActivate: [authGuard]
  },
  {
    path: 'uploads',
    component: UploadsComponent,
    canActivate: [authGuard]
  },
  {
    path: 'uploads-audit',
    component: UploadsAuditComponent,
    canActivate: [authGuard]
  },
  {
    path: 'uploads-purge',
    component: UploadsPurgeComponent,
    canActivate: [authGuard]
  },
  {
    path: 'outstanding',
    component: OutstandingComponent,
    canActivate: [authGuard]
  },
  {
    path: 'payment-dates',
    component: PaymentDatesComponent,
    canActivate: [authGuard]
  },
  {
    path: 'sales-details',
    component: SalesDetailsComponent,
    canActivate: [authGuard]
  },
  {
    path: 'sales-visualization',
    component: SalesVisualizationComponent,
    canActivate: [authGuard]
  },
  {
    path: 'customer-locations',
    component: CustomerLocationsComponent,
    canActivate: [authGuard]
  },
  {
    path: 'access-control',
    component: AccessControlComponent,
    canActivate: [adminGuard]
  },
  {
    path: 'sessions',
    component: SessionsComponent,
    canActivate: [adminGuard]
  },
  {
    path: '**',
    redirectTo: 'login'
  }
];

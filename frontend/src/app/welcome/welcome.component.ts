import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../auth/auth.service';
import { ApiService } from '../services/api.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { PageStateComponent } from '../shared/page-state/page-state.component';
import { messageFromHttpError } from '../shared/api-error.util';

@Component({
  selector: 'app-welcome',
  standalone: true,
  imports: [CommonModule, PageStateComponent],
  templateUrl: './welcome.component.html',
  styleUrl: './welcome.component.css'
})
export class WelcomeComponent implements OnInit {
  displayName = this.auth.getDisplayName();
  statusLoading = true;
  analyticsEnabled = false;
  analyticsMessage = '';
  canUpload = false;
  canAccessInvoicePage = false;
  canAccessDetailsPage = false;
  canAccessOutstandingPage = false;
  canWhatsappBroadcast = false;
  canAccessAiAgent = false;

  constructor(
    private auth: AuthService,
    private api: ApiService,
    private router: Router,
    private permissionService: PermissionService,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.canUpload = this.permissionService.canAccessFileUpload();
    this.canAccessInvoicePage = this.permissionService.canAccessInvoicePage();
    this.canAccessDetailsPage = this.permissionService.canAccessDetailsPage();
    this.canAccessOutstandingPage = this.permissionService.canAccessOutstandingPage();
    this.canWhatsappBroadcast = this.permissionService.canAccessWhatsappBroadcast();
    this.canAccessAiAgent = this.permissionService.canAccessAiAgent();

    this.api.getUploadStatus().subscribe({
      next: (status) => {
        this.statusLoading = false;
        this.analyticsEnabled = status.ready;
        if (status.ready) {
          this.analyticsMessage = 'Ready to view analytics.';
        } else if (status.hasDetailed && !status.hasReceivable) {
          this.analyticsMessage = 'Missing: ReceivableAgeingReport file.';
        } else if (!status.hasDetailed && status.hasReceivable) {
          this.analyticsMessage = 'Missing: DetailedSalesInvoices file.';
        } else {
          this.analyticsMessage = 'Upload both files to enable analytics.';
        }
      },
      error: (err: HttpErrorResponse) => {
        this.statusLoading = false;
        this.analyticsEnabled = false;
        this.analyticsMessage = messageFromHttpError(err, 'Unable to load upload status.');
        this.notifications.showError(this.analyticsMessage);
      }
    });
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  goToUpload(): void {
    this.router.navigateByUrl('/upload');
  }

  goToOutstanding(): void {
    if (!this.analyticsEnabled) {
      return;
    }
    this.router.navigateByUrl('/outstanding');
  }

  goToOutstandingDue(): void {
    if (!this.analyticsEnabled) {
      return;
    }
    this.router.navigateByUrl('/outstanding-due');
  }

  goToWhatsappOutreach(): void {
    this.router.navigateByUrl('/whatsapp-outreach');
  }

  goToAiAgent(): void {
    this.router.navigateByUrl('/ai-agent');
  }

  goToSalesDetails(): void {
    if (!this.analyticsEnabled) {
      return;
    }
    this.router.navigateByUrl('/sales-details');
  }
}

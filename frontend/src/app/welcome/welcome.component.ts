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
  canAccessBillExtract = false;
  canAccessUploadsList = false;
  canAccessUploadAudit = false;
  canAccessHardDelete = false;
  canAccessRateList = false;
  canAccessSalesVisualization = false;
  canAccessCreditRisk = false;
  canAccessCustomerLocations = false;
  canAccessDashboard = false;
  canAccessSessions = false;
  canAccessAccessControl = false;

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
    this.canAccessBillExtract = this.permissionService.canAccessBillExtract();
    this.canAccessUploadsList = this.permissionService.canAccessUploadsList();
    this.canAccessUploadAudit = this.permissionService.canAccessUploadAudit();
    this.canAccessHardDelete = this.permissionService.canAccessHardDelete();
    this.canAccessRateList = this.permissionService.canAccessRateList();
    this.canAccessSalesVisualization = this.permissionService.canAccessSalesVisualization();
    this.canAccessCreditRisk = this.permissionService.canAccessCreditRisk();
    this.canAccessCustomerLocations = this.permissionService.canAccessCustomerLocations();
    this.canAccessDashboard = this.permissionService.canAccessRoute('/dashboard');
    this.canAccessSessions = this.permissionService.canAccessRoute('/sessions');
    this.canAccessAccessControl = this.permissionService.canAccessRoute('/access-control');

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
          this.analyticsMessage = 'Upload all three files (Detailed Sales, Receivable Ageing, Customer Ledger) to enable analytics.';
        }
      },
      error: (err: HttpErrorResponse) => {
        this.statusLoading = false;
        // Rate-limit / transport errors must not lock Invoice, Details, or Outstanding Due.
        this.analyticsEnabled = true;
        this.analyticsMessage = '';
        if (err.status !== 429) {
          this.notifications.showError(messageFromHttpError(err, 'Unable to load upload status.'));
        }
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

  goToBillExtract(): void {
    this.router.navigateByUrl('/bill-extract');
  }

  goToSalesDetails(): void {
    if (!this.analyticsEnabled) {
      return;
    }
    this.router.navigateByUrl('/sales-details');
  }

  goToUploads(): void {
    this.router.navigateByUrl('/uploads');
  }

  goToUploadsAudit(): void {
    this.router.navigateByUrl('/uploads-audit');
  }

  goToUploadsPurge(): void {
    this.router.navigateByUrl('/uploads-purge');
  }

  goToRateList(): void {
    this.router.navigateByUrl('/rate-list');
  }

  goToSalesVisualization(): void {
    if (!this.analyticsEnabled) {
      return;
    }
    this.router.navigateByUrl('/sales-visualization');
  }

  goToCreditRisk(): void {
    this.router.navigateByUrl('/credit-risk');
  }

  goToCustomerLocations(): void {
    if (!this.analyticsEnabled) {
      return;
    }
    this.router.navigateByUrl('/customer-locations');
  }

  goToDashboard(): void {
    this.router.navigateByUrl('/dashboard');
  }

  goToSessions(): void {
    this.router.navigateByUrl('/sessions');
  }

  goToAccessControl(): void {
    this.router.navigateByUrl('/access-control');
  }
}

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ApiService } from '../services/api.service';
import { PermissionService } from '../auth/permission.service';

@Component({
  selector: 'app-welcome',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './welcome.component.html',
  styleUrl: './welcome.component.css'
})
export class WelcomeComponent implements OnInit {
  displayName = this.auth.getDisplayName();
  analyticsEnabled = false;
  analyticsMessage = '';
  canUpload = false;
  canAccessInvoicePage = false;
  canAccessDetailsPage = false;
  canAccessOutstandingPage = false;

  constructor(
    private auth: AuthService,
    private api: ApiService,
    private router: Router,
    private permissionService: PermissionService
  ) {}

  ngOnInit(): void {
    this.canUpload = this.permissionService.canAccessFileUpload();
    this.canAccessInvoicePage = this.permissionService.canAccessInvoicePage();
    this.canAccessDetailsPage = this.permissionService.canAccessDetailsPage();
    this.canAccessOutstandingPage = this.permissionService.canAccessOutstandingPage();
    
    this.analyticsMessage = 'Checking upload status...';
    this.api.getUploadStatus().subscribe({
      next: (status) => {
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
      error: () => {
        this.analyticsEnabled = false;
        this.analyticsMessage = 'Unable to load upload status.';
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

  goToPaymentDates(): void {
    if (!this.analyticsEnabled) {
      return;
    }
    this.router.navigateByUrl('/payment-dates');
  }

  goToSalesDetails(): void {
    if (!this.analyticsEnabled) {
      return;
    }
    this.router.navigateByUrl('/sales-details');
  }

}


import { Injectable } from '@angular/core';
import { AuthService } from './auth.service';
import { ROUTE_PERMISSIONS } from './permissions.config';

@Injectable({ providedIn: 'root' })
export class PermissionService {
  constructor(private authService: AuthService) {}

  // Dynamic permission check - automatically works with new permissions
  hasPermission(permission: keyof import('./auth.service').UserPermissions): boolean {
    return this.authService.hasPermission(permission);
  }

  // Convenience methods for backward compatibility and type safety
  canAccessFileUpload(): boolean {
    return this.hasPermission('fileUpload');
  }

  canAccessHardDelete(): boolean {
    return this.hasPermission('hardDelete');
  }

  canAccessInvoicePage(): boolean {
    return this.hasPermission('invoicePage');
  }

  canAccessDetailsPage(): boolean {
    return this.hasPermission('detailsPage');
  }

  canDownloadWholeProject(): boolean {
    return this.hasPermission('wholeProjectDownload');
  }

  canAccessOutstandingPage(): boolean {
    return this.hasPermission('outstandingPage');
  }

  canEditPaymentDate(): boolean {
    return this.hasPermission('paymentDateEdit');
  }

  canChangeWhatsappDate(): boolean {
    return this.hasPermission('whatsappDateChange');
  }

  canChangeFollowUp(): boolean {
    return this.hasPermission('followUpChange');
  }

  canAccessRateList(): boolean {
    return this.hasPermission('rateListPage');
  }

  canAccessCustomerLocations(): boolean {
    return this.hasPermission('customerLocations');
  }

  // Check if user can access a route
  canAccessRoute(route: string): boolean {
    const permission = ROUTE_PERMISSIONS[route];
    if (!permission) {
      return true; // No permission required
    }
    if (permission === 'admin') {
      return this.isAdmin();
    }
    return this.hasPermission(permission);
  }

  isAdmin(): boolean {
    return this.authService.isAdmin();
  }
}


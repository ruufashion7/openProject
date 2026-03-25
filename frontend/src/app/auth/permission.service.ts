import { Injectable } from '@angular/core';
import { AuthService } from './auth.service';
import { ROUTE_PERMISSIONS, getPermissionLabel } from './permissions.config';
import { NotificationService } from '../shared/notification.service';

@Injectable({ providedIn: 'root' })
export class PermissionService {
  constructor(
    private authService: AuthService,
    private notifications: NotificationService
  ) {}

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

  /** User can open Details or Outstanding flows (required for customer master edits). */
  canAccessDetailsOrOutstanding(): boolean {
    return this.hasPermission('detailsPage') || this.hasPermission('outstandingPage');
  }

  canEditCustomerCategory(): boolean {
    return this.canAccessDetailsOrOutstanding() && this.hasPermission('customerCategoryEdit');
  }

  /** Read notes on Details / Outstanding (no extra role). */
  canViewCustomerNotes(): boolean {
    return this.canAccessDetailsOrOutstanding();
  }

  /** Add, edit, or delete notes — requires Customer Notes Edit in Access Control. */
  canEditCustomerNotes(): boolean {
    return this.canAccessDetailsOrOutstanding() && this.hasPermission('customerNotesEdit');
  }

  canEditCustomerLocation(): boolean {
    return this.canAccessDetailsOrOutstanding() && this.hasPermission('customerLocationEdit');
  }

  canAccessRateList(): boolean {
    return this.hasPermission('rateListPage');
  }

  /** Excel template + bulk upload on Rate List (requires Rate List page + this flag). */
  canUploadRateListFiles(): boolean {
    return this.canAccessRateList() && this.hasPermission('rateListUpload');
  }

  canAccessCustomerLocations(): boolean {
    return this.hasPermission('customerLocations');
  }

  canAccessSalesVisualization(): boolean {
    return this.hasPermission('salesVisualization');
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

  /**
   * Same toast as follow-up / other guarded actions: ask admin to enable the permission in Access Control.
   */
  notifyRoleDenied(actionDescription: string, permissionKey: keyof import('./auth.service').UserPermissions): void {
    this.notifications.showRoleRequired(actionDescription, getPermissionLabel(permissionKey));
  }

  /** Hover / screen-reader hint when control is interactive but action is denied (pair with dimmed styling). */
  accessDeniedTooltip(
    hasPermission: boolean,
    permissionKey: keyof import('./auth.service').UserPermissions
  ): string {
    if (hasPermission) {
      return '';
    }
    return `No permission — enable "${getPermissionLabel(permissionKey)}" in Access Control`;
  }
}


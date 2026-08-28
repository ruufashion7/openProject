import { Component, OnInit, OnDestroy, ChangeDetectorRef, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';
import { ApiService, CustomerLedgerEntry, CustomerSummaryResponse, CustomerNote, ExcludedCustomerView, RetainedCustomerView } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { LocationInputComponent, LocationData } from '../shared/location-input/location-input.component';
import { HttpErrorResponse } from '@angular/common/http';
import * as L from 'leaflet';
import { configureLeafletDefaults } from '../shared/leaflet-defaults';

configureLeafletDefaults();
import * as XLSX from 'xlsx';
import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';
import {
  addWatermark,
  buildExcelWatermarkRow,
  setExcelPrintTitleTopRow,
} from '../shared/export-watermark';
import { formatInrForExcel, formatInrForPdf } from '../shared/format-inr-export';
import {
  getPaymentDateBorderClass as paymentDateBorderClass,
  getPaymentDateTone as paymentDateTone,
  isPaymentDatePast,
  isValidPaymentDateFormat,
  normalizeToDayMonth,
  PAYMENT_DATE_SAVE_DEBOUNCE_MS,
  todayIsoDate,
  toIsoDate
} from '../shared/payment-date.util';
import { ensurePdfUnicodeFonts, PDF_UNICODE_FONT } from '../shared/pdf-unicode-font';
import { PageStateComponent } from '../shared/page-state/page-state.component';
import { formatCoordinatesDms } from '../shared/coordinates.util';
import {
  formatPhoneDisplay,
  formatPhoneForTel,
  formatPhoneForWhatsApp
} from '../shared/phone.util';

@Component({
  selector: 'app-outstanding',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, LocationInputComponent, PageStateComponent],
  templateUrl: './outstanding.component.html',
  styleUrl: './outstanding.component.css'
})
export class OutstandingComponent implements OnInit, OnDestroy {
  formatPhoneDisplay = formatPhoneDisplay;

  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';
  ready = false;
  customerQuery = '';
  customerSuggestions: string[] = [];
  phoneSuggestions: string[] = [];
  /** Single API call returns up to this many suggestions; UI scrolls locally. */
  readonly customerSuggestLimit = 500;
  private customerSuggestQuery = '';
  customerStatus = '';
  customerStatusIsError = false;
  customerSummary?: CustomerSummaryResponse;
  customerLedger: CustomerLedgerEntry[] = [];
  ledgerFilter: 'paid' | 'unpaid' | 'all' = 'all';
  selectedCustomerName: string | null = null;
  selectedPhoneNumber: string | null = null;
  paymentDate: string | null = null;
  paymentDateEdit: string = '';
  whatsappStatus: 'not sent' | 'sent' | 'delivered' | null = null;
  whatsappStatusEdit: 'not sent' | 'sent' | 'delivered' = 'not sent';
  customerCategory: 'semi-wholesale' | 'A' | 'B' | 'C' | null = null;
  customerCategoryEdit: 'semi-wholesale' | 'A' | 'B' | 'C' = 'A';
  creditLimitInput = '';
  followUpFlag: boolean = false;
  followUpFlagEdit: boolean = false;
  highlightedIndex: number = -1;
  showSuggestions: boolean = false;
  editingLocation: boolean = false;
  locationAddress: string = '';
  locationLatitude: number | null = null;
  locationLongitude: number | null = null;
  @ViewChild('locationMapPreview', { static: false }) locationMapPreview!: ElementRef;
  locationMap: L.Map | null = null;
  locationMarker: L.Marker | null = null;
  private mapInitialized: boolean = false;
  addressExpanded: boolean = false;
  coordinatesExpanded: boolean = false;
  mapExpanded: boolean = false;
  private customerTimer?: number;
  private phoneTimer?: number;
  private messageTimer?: number;
  private paymentDateSaveTimer?: number;
  private whatsappStatusSaveTimer?: number;
  private customerCategorySaveTimer?: number;
  private creditLimitSaveTimer?: number;
  private followUpSaveTimer?: number;
  private isProcessingDateChange = false;
  private readonly selectedCustomerKey = 'openProject.selectedCustomer';
  canDownloadWholeProject = false;
  canEditPaymentDate = false;
  canChangeWhatsappDate = false;
  canChangeFollowUp = false;
  /** Customer master fields (require Details or Outstanding + specific permission). */
  canEditCustomerCategory = false;
  canEditCustomerLimit = false;
  canViewCustomerNotes = false;
  canEditCustomerNotes = false;
  canEditCustomerLocation = false;
  canExcludeCustomer = false;
  isCurrentCustomerExcluded = false;
  currentExcludedKey: string | null = null;
  canRetainCustomer = false;
  isCurrentCustomerRetained = false;
  currentRetainedKey: string | null = null;
  
  // Subscription management
  private destroy$ = new Subject<void>();
  // Customer Notes
  customerNotes: CustomerNote[] = [];
  readonly maxCustomerNotes = 6;
  notesExpanded: boolean = false;
  editingNoteId: string | null = null;
  editingNoteContent: string = '';
  newNoteContent: string = '';
  isLoadingNotes: boolean = false;

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router,
    private cdr: ChangeDetectorRef,
    public permissionService: PermissionService,
    public notificationService: NotificationService
  ) {}

  ngOnInit(): void {
    this.auth.refreshSessionPermissionsFromServer()
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        if (!this.auth.getToken()) {
          return;
        }
        if (!this.permissionService.canAccessDetailsPage()) {
          this.notificationService.showPermissionError();
          this.router.navigateByUrl('/welcome');
          return;
        }

        this.applyPermissionFlags();
        this.status = 'loading';
        this.api.getUploadStatus()
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: (status) => {
              this.ready = status.ready ?? (status.hasDetailed && status.hasReceivable);
              this.status = 'idle';
              this.setMessage(this.ready
                ? 'Latest uploads available.'
                : 'Latest uploads not available.');
            },
            error: (err: HttpErrorResponse) => {
              if (err.status === 401) {
                this.status = 'failed';
                this.setMessage('Session expired. Please login again.');
                this.logout();
                return;
              }
              this.status = 'failed';
              this.setMessage('Unable to load upload status.');
            }
          });

        this.initCustomerSelectionFromStorage();
      });
  }

  refreshPage(): void {
    window.location.reload();
  }

  private applyPermissionFlags(): void {
    this.canDownloadWholeProject = this.permissionService.canDownloadWholeProject();
    this.canEditPaymentDate = this.permissionService.canEditPaymentDate();
    this.canChangeWhatsappDate = this.permissionService.canChangeWhatsappDate();
    this.canChangeFollowUp = this.permissionService.canChangeFollowUp();
    this.canEditCustomerCategory = this.permissionService.canEditCustomerCategory();
    this.canEditCustomerLimit = this.permissionService.canEditCustomerLimit();
    this.canViewCustomerNotes = this.permissionService.canViewCustomerNotes();
    this.canEditCustomerNotes = this.permissionService.canEditCustomerNotes();
    this.canEditCustomerLocation = this.permissionService.canEditCustomerLocation();
    this.canExcludeCustomer = this.permissionService.canExcludeCustomer();
    this.canRetainCustomer = this.permissionService.canRetainCustomer();
  }

  private initCustomerSelectionFromStorage(): void {
    // SECURITY: Do NOT read customer from URL query parameters
    // Remove customer from URL if present
    const urlCustomer = this.getCustomerFromQuery();
    if (urlCustomer) {
      // Remove customer from URL for security
      this.updateUrlWithoutCustomer();
      // Load customer from sessionStorage/localStorage instead
      const savedCustomer = this.getSavedCustomer();
      if (savedCustomer && this.selectedCustomerName !== savedCustomer) {
        this.selectCustomer(savedCustomer);
      } else if (!savedCustomer) {
        this.selectedCustomerName = null;
      }
    } else {
      // Check localStorage for saved customer
      const savedCustomer = this.getSavedCustomer();
      if (savedCustomer && this.selectedCustomerName !== savedCustomer) {
        this.selectCustomer(savedCustomer);
      } else if (!savedCustomer) {
        this.selectedCustomerName = null;
      }
    }
  }

  /**
   * Backend normalizes customer names for customer_master keys; the summary may return a canonical
   * Excel name that differs from the search string. Saves must use that same string or updates
   * hit a different document than the one the summary reads.
   */
  private getCustomerNameForMasterWrites(): string | null {
    // Prefer canonical name from summary when present; otherwise use the selected display name
    // (needed for ₹0 / no-receivable customers where summary.found is false).
    const fromSummary = this.customerSummary?.customer?.trim();
    if (fromSummary) {
      return fromSummary;
    }
    return this.selectedCustomerName?.trim() ?? null;
  }

  private applyCanonicalCustomerNameFromSummary(summary: CustomerSummaryResponse): void {
    const c = summary.customer?.trim();
    if (!c) {
      return;
    }
    if (this.selectedCustomerName !== c) {
      this.selectedCustomerName = c;
      this.saveCustomer(c);
      this.updateUrlWithCustomer(c);
    }
  }

  /** Drop pending debounced saves so they cannot fire after switching customers or clearing summary. */
  private clearPendingMasterWriteTimers(): void {
    if (this.paymentDateSaveTimer) {
      window.clearTimeout(this.paymentDateSaveTimer);
      this.paymentDateSaveTimer = undefined;
    }
    if (this.whatsappStatusSaveTimer) {
      window.clearTimeout(this.whatsappStatusSaveTimer);
      this.whatsappStatusSaveTimer = undefined;
    }
    if (this.customerCategorySaveTimer) {
      window.clearTimeout(this.customerCategorySaveTimer);
      this.customerCategorySaveTimer = undefined;
    }
    if (this.followUpSaveTimer) {
      window.clearTimeout(this.followUpSaveTimer);
      this.followUpSaveTimer = undefined;
    }
  }

  onCustomerQueryChange(value: string): void {
    this.customerQuery = value;
    this.highlightedIndex = -1;
    if (this.customerTimer) {
      window.clearTimeout(this.customerTimer);
    }
    if (this.phoneTimer) {
      window.clearTimeout(this.phoneTimer);
    }
    if (value.trim().length < 3) {
      this.customerSuggestions = [];
      this.customerSuggestQuery = '';
      this.phoneSuggestions = [];
      this.showSuggestions = false;
      this.customerStatus = 'Type at least 3 characters to search.';
      this.customerStatusIsError = false;
      this.customerSummary = undefined;
      return;
    }
    this.customerStatus = 'Searching...';
    this.customerStatusIsError = false;
    const query = value.trim();
    this.customerSuggestQuery = query;
    this.customerSuggestions = [];

    // Search for both customer names and phone numbers
    let customerSuggestionsReceived = false;
    let phoneSuggestionsReceived = false;

    const checkAndUpdateStatus = () => {
      if (customerSuggestionsReceived && phoneSuggestionsReceived) {
        // Remove duplicate phone numbers that already appear in customer suggestions
        const customerSet = new Set(this.customerSuggestions.map(c => c.toLowerCase().trim()));
        this.phoneSuggestions = this.phoneSuggestions.filter(phone => {
          const phoneTrimmed = phone.trim();
          return !customerSet.has(phoneTrimmed.toLowerCase());
        });

        const totalSuggestions = this.customerSuggestions.length + this.phoneSuggestions.length;
        this.showSuggestions = totalSuggestions > 0;
        this.customerStatus = totalSuggestions ? '' : 'No results found.';
        this.customerStatusIsError = totalSuggestions === 0;
      }
    };

    // One API call — up to 500 names; dropdown scrolls locally
    this.customerTimer = window.setTimeout(() => {
      this.api.getCustomerSuggestions(query, this.customerSuggestLimit).subscribe({
        next: (suggestions) => {
          if (this.customerSuggestQuery !== query) {
            return;
          }
          this.customerSuggestions = suggestions ?? [];
          customerSuggestionsReceived = true;
          checkAndUpdateStatus();
        },
        error: (err: HttpErrorResponse) => {
          if (this.customerSuggestQuery !== query) {
            return;
          }
          this.customerSuggestions = [];
          this.customerSuggestQuery = '';
          customerSuggestionsReceived = true;
          if (err.status === 401) {
            this.customerStatus = 'Session expired. Please login again.';
            this.customerStatusIsError = true;
            this.logout();
            return;
          }
          checkAndUpdateStatus();
        }
      });
    }, 300);

    // Search phone numbers
    this.phoneTimer = window.setTimeout(() => {
      this.api.getPhoneSuggestions(query, 20).subscribe({
        next: (suggestions) => {
          this.phoneSuggestions = suggestions;
          phoneSuggestionsReceived = true;
          checkAndUpdateStatus();
        },
        error: (err: HttpErrorResponse) => {
          this.phoneSuggestions = [];
          phoneSuggestionsReceived = true;
          if (err.status === 401) {
            this.customerStatus = 'Session expired. Please login again.';
            this.customerStatusIsError = true;
            this.logout();
            return;
          }
          checkAndUpdateStatus();
        }
      });
    }, 300);
  }

  onCustomerKeydown(event: KeyboardEvent): void {
    const totalSuggestions = this.customerSuggestions.length + this.phoneSuggestions.length;
    if (!totalSuggestions) {
      return;
    }

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        const maxIndex = totalSuggestions - 1;
        this.highlightedIndex = Math.min(this.highlightedIndex + 1, maxIndex);
        this.scrollToHighlighted();
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.highlightedIndex = Math.max(this.highlightedIndex - 1, -1);
        this.scrollToHighlighted();
        break;
      case 'Enter':
        event.preventDefault();
        if (this.highlightedIndex >= 0) {
          // User selected a suggestion
          if (this.highlightedIndex < this.customerSuggestions.length) {
            this.selectCustomer(this.customerSuggestions[this.highlightedIndex]);
          } else {
            const phoneIndex = this.highlightedIndex - this.customerSuggestions.length;
            if (phoneIndex >= 0 && phoneIndex < this.phoneSuggestions.length) {
              this.selectPhone(this.phoneSuggestions[phoneIndex]);
            }
          }
        } else if (this.customerQuery.trim().length >= 3) {
          // User pressed Enter without selecting a suggestion - search directly
          const query = this.customerQuery.trim();
          // Check if it's a phone number (all digits, length >= 10)
          if (/^\d{10,}$/.test(query)) {
            this.selectPhone(query);
          } else {
            this.selectCustomer(query);
          }
        }
        break;
      case 'Escape':
        this.showSuggestions = false;
        this.highlightedIndex = -1;
        break;
    }
  }

  onCustomerInputFocus(): void {
    const totalSuggestions = this.customerSuggestions.length + this.phoneSuggestions.length;
    if (totalSuggestions > 0) {
      this.showSuggestions = true;
    }
  }

  onCustomerInputBlur(): void {
    // Delay hiding suggestions to allow click events to fire
    setTimeout(() => {
      this.showSuggestions = false;
      this.highlightedIndex = -1;
    }, 200);
  }

  private scrollToHighlighted(): void {
    // Scroll to highlighted item if needed
    setTimeout(() => {
      const highlightedElement = document.querySelector('.suggestions li.highlighted');
      if (highlightedElement) {
        highlightedElement.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
    }, 0);
  }

  highlightMatch(text: string, query: string): string {
    if (!query || !text) {
      // SECURITY: Escape HTML to prevent XSS via innerHTML
      return this.escapeHtml(text || '');
    }
    // SECURITY: Escape HTML entities in text BEFORE applying highlight markup
    // This prevents XSS if customer names contain malicious HTML/script tags
    const escaped = this.escapeHtml(text);
    const escapedQuery = this.escapeHtml(query);
    const regex = new RegExp(`(${escapedQuery.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi');
    return escaped.replace(regex, '<mark>$1</mark>');
  }

  private escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  selectCustomer(name: string): void {
    // Check if the name is actually a phone number (all digits, length >= 10)
    const isPhoneNumber = /^\d{10,}$/.test(name.trim());
    
    // Prevent duplicate selection of the same customer (but allow if it's a phone number that will resolve to a name)
    if (!isPhoneNumber && this.selectedCustomerName === name) {
      return;
    }

    this.clearPendingMasterWriteTimers();
    
    this.customerQuery = name;
    this.customerSuggestions = [];
    this.phoneSuggestions = [];
    this.showSuggestions = false;
    this.highlightedIndex = -1;
    this.customerStatus = '';
    this.customerStatusIsError = false;
    
    // If it's a phone number, don't set selectedCustomerName yet - wait for API response
    // Otherwise, set it immediately
    if (isPhoneNumber) {
      this.selectedCustomerName = null;
      this.selectedPhoneNumber = name;
      this.resetExclusionStatus();
      this.resetRetentionStatus();
    } else {
      this.selectedCustomerName = name;
      this.selectedPhoneNumber = null;
      // Load ignore/retain status immediately (do not wait for receivable summary)
      this.refreshExclusionStatus();
    }
    
    this.customerSummary = undefined;
    this.customerLedger = [];
    this.paymentDate = null;
    this.whatsappStatus = null;
    this.editingLocation = false;
    this.syncLocationFieldsFromSummary();
    if (!isPhoneNumber) {
      const currentSaved = this.getSavedCustomer();
      if (currentSaved !== name) {
        this.saveCustomer(name);
      }
      
      const currentUrlCustomer = this.getCustomerFromQuery();
      if (currentUrlCustomer !== name) {
        this.updateUrlWithCustomer(name);
      }
    }
    
    this.api.getCustomerSummary(name).subscribe({
      next: (summary) => {
        this.customerSummary = summary;
        
        // If we searched by phone number (or a phone number was passed as customer), 
        // update selectedCustomerName with the actual customer name from response
        if (isPhoneNumber || (name.trim().match(/^\d{10,}$/))) {
          const customerName = (summary.customer && typeof summary.customer === 'string') 
            ? summary.customer.trim() 
            : '';
          
          if (customerName && customerName.length > 0 && !customerName.match(/^\d{10,}$/)) {
            // Valid customer name found (not a phone number)
            this.selectedCustomerName = customerName;
            this.selectedPhoneNumber = null;
            // Save to localStorage and update URL
            this.saveCustomer(customerName);
            this.updateUrlWithCustomer(customerName);
            this.cdr.detectChanges();
          } else {
            // No valid customer name found, keep showing phone number
            this.selectedCustomerName = null;
            this.selectedPhoneNumber = name;
          }
        }
        
        if (!summary.found) {
          this.customerStatus = 'No receivable ageing for this customer (can still ignore/retain).';
          this.customerStatusIsError = false;
        }
        // Get payment date and WhatsApp status from customer summary
        if (summary.nextPaymentDate) {
          this.paymentDate = summary.nextPaymentDate;
          this.paymentDateEdit = summary.nextPaymentDate;
        } else {
          this.paymentDate = null;
          this.paymentDateEdit = '';
        }
        // Set WhatsApp status - use 'not sent' as default if null/undefined
        const status = summary.whatsAppStatus;
        this.whatsappStatus = (status && status.trim() !== '') ? status as 'not sent' | 'sent' | 'delivered' : 'not sent';
        this.whatsappStatusEdit = this.whatsappStatus;
        // Set customer category - use 'A' as default if null/undefined
        const category = summary.customerCategory;
        this.customerCategory = (category && category.trim() !== '') ? category as 'semi-wholesale' | 'A' | 'B' | 'C' : 'A';
        this.customerCategoryEdit = this.customerCategory;
        // Set follow-up flag
        this.followUpFlag = summary.needsFollowUp ?? false;
        this.followUpFlagEdit = this.followUpFlag;
        this.syncCreditLimitFieldsFromSummary(summary);
        this.syncLocationFieldsFromSummary(summary);
        this.applyCanonicalCustomerNameFromSummary(summary);
        this.refreshExclusionStatus();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
          return;
        }
        this.customerStatus = 'Unable to load customer summary.';
        this.customerStatusIsError = true;
        // Still allow ignore/retain using the selected name
        this.refreshExclusionStatus();
      }
    });

    // Call ledger API - if it's a phone number, pass it as phone parameter, otherwise as customer
    if (isPhoneNumber) {
      this.api.getCustomerLedger(undefined, name).subscribe({
        next: (entries) => {
          this.customerLedger = entries;
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.customerStatus = 'Session expired. Please login again.';
            this.customerStatusIsError = true;
            this.logout();
            return;
          }
          this.customerStatus = 'Unable to load customer details.';
          this.customerStatusIsError = true;
        }
      });
    } else {
      this.api.getCustomerLedger(name).subscribe({
        next: (entries) => {
          this.customerLedger = entries;
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.customerStatus = 'Session expired. Please login again.';
            this.customerStatusIsError = true;
            this.logout();
            return;
          }
          this.customerStatus = 'Unable to load customer details.';
          this.customerStatusIsError = true;
        }
      });
    }
  }

  selectPhone(phone: string): void {
    this.clearPendingMasterWriteTimers();
    this.customerQuery = phone;
    this.phoneSuggestions = [];
    this.customerSuggestions = [];
    this.showSuggestions = false;
    this.highlightedIndex = -1;
    this.customerStatus = '';
    this.customerStatusIsError = false;
    // Temporarily show phone number, will be replaced by customer name from API
    this.selectedPhoneNumber = phone;
    this.selectedCustomerName = null;
    this.customerSummary = undefined;
    this.customerLedger = [];
    this.paymentDate = null;
    this.whatsappStatus = null;
    this.customerNotes = [];
    this.editingLocation = false;
    this.syncLocationFieldsFromSummary();

    this.api.getCustomerSummary(undefined, phone).subscribe({
      next: (summary) => {
        this.customerSummary = summary;
        // Always set customer name if it exists in the response - this will replace the phone number
        // The backend returns the customer name in summary.customer when searching by phone
        // Check both summary.customer and ensure it's not empty
        const customerName = (summary.customer && typeof summary.customer === 'string') 
          ? summary.customer.trim() 
          : '';
        
        if (customerName && customerName.length > 0) {
          // Set customer name and clear phone number - this matches selectCustomer behavior exactly
          this.selectedCustomerName = customerName;
          this.selectedPhoneNumber = null;
          // Save to localStorage and update URL
          this.saveCustomer(customerName);
          this.updateUrlWithCustomer(customerName);
          // Trigger change detection to ensure UI updates immediately
          this.cdr.detectChanges();
        } else {
          // If no customer name found, keep showing the phone number as fallback
          this.selectedCustomerName = null;
          this.selectedPhoneNumber = phone;
        }
        if (!summary.found) {
          this.customerStatus = 'No data found for this phone number.';
          this.customerStatusIsError = true;
        }
        // Get payment date and WhatsApp status from customer summary
        if (summary.nextPaymentDate) {
          this.paymentDate = summary.nextPaymentDate;
          this.paymentDateEdit = summary.nextPaymentDate;
        } else {
          this.paymentDate = null;
          this.paymentDateEdit = '';
        }
        // Set WhatsApp status - use 'not sent' as default if null/undefined
        const status = summary.whatsAppStatus;
        this.whatsappStatus = (status && status.trim() !== '') ? status as 'not sent' | 'sent' | 'delivered' : 'not sent';
        this.whatsappStatusEdit = this.whatsappStatus;
        // Set customer category - use 'A' as default if null/undefined
        const category = summary.customerCategory;
        this.customerCategory = (category && category.trim() !== '') ? category as 'semi-wholesale' | 'A' | 'B' | 'C' : 'A';
        this.customerCategoryEdit = this.customerCategory;
        // Set follow-up flag
        this.followUpFlag = summary.needsFollowUp ?? false;
        this.followUpFlagEdit = this.followUpFlag;
        this.syncCreditLimitFieldsFromSummary(summary);
        this.syncLocationFieldsFromSummary(summary);
        this.applyCanonicalCustomerNameFromSummary(summary);
        this.refreshExclusionStatus();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
          return;
        }
        this.customerStatus = 'Unable to load customer summary.';
        this.customerStatusIsError = true;
      }
    });

    this.api.getCustomerLedger(undefined, phone).subscribe({
      next: (entries) => {
        this.customerLedger = entries;
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
          return;
        }
        this.customerStatus = 'Unable to load customer details.';
        this.customerStatusIsError = true;
      }
    });

    if (this.canViewCustomerNotes) {
      this.loadNotes();
    } else {
      this.customerNotes = [];
    }
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  private resetExclusionStatus(): void {
    this.isCurrentCustomerExcluded = false;
    this.currentExcludedKey = null;
  }

  private resetRetentionStatus(): void {
    this.isCurrentCustomerRetained = false;
    this.currentRetainedKey = null;
  }

  private refreshExclusionStatus(): void {
    const name = this.getCustomerNameForMasterWrites();
    if (!name) {
      this.resetExclusionStatus();
      this.resetRetentionStatus();
      return;
    }
    this.api.getExcludedCustomers()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (list) => {
          const match = this.findExcludedMatch(list ?? [], name);
          this.isCurrentCustomerExcluded = !!match;
          this.currentExcludedKey = match?.customerKey ?? null;
        },
        error: () => {
          this.resetExclusionStatus();
        }
      });
    this.api.getRetainedCustomers()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (list) => {
          const match = this.findRetainedMatch(list ?? [], name);
          this.isCurrentCustomerRetained = !!match;
          this.currentRetainedKey = match?.customerKey ?? null;
        },
        error: () => {
          this.resetRetentionStatus();
        }
      });
  }

  private findExcludedMatch(list: ExcludedCustomerView[], displayName: string): ExcludedCustomerView | undefined {
    const targetKey = this.normalizeCustomerKey(displayName);
    return list.find((item) => {
      const nameKey = this.normalizeCustomerKey(item.customerName || '');
      const key = this.normalizeCustomerKey(item.customerKey || '');
      return nameKey === targetKey || key === targetKey;
    });
  }

  private findRetainedMatch(list: RetainedCustomerView[], displayName: string): RetainedCustomerView | undefined {
    const targetKey = this.normalizeCustomerKey(displayName);
    return list.find((item) => {
      const nameKey = this.normalizeCustomerKey(item.customerName || '');
      const key = this.normalizeCustomerKey(item.customerKey || '');
      return nameKey === targetKey || key === targetKey;
    });
  }

  /** Same normalization as backend CustomerIdentity.normalizeKey */
  private normalizeCustomerKey(value: string): string {
    return value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, ' ')
      .trim()
      .replace(/\s+/g, ' ');
  }

  private apiErrorMessage(err: HttpErrorResponse, fallback: string): string {
    const body = err?.error;
    if (body && typeof body === 'object' && typeof (body as { error?: string }).error === 'string'
        && (body as { error: string }).error.trim()) {
      return (body as { error: string }).error;
    }
    return fallback;
  }

  ignoreCurrentCustomer(): void {
    const name = this.getCustomerNameForMasterWrites();
    if (!name) {
      return;
    }
    if (!this.canExcludeCustomer) {
      this.permissionService.notifyRoleDenied('ignore customers', 'customerExcludeEdit');
      return;
    }
    this.api.excludeCustomer(name)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notificationService.showSuccess(`${name} is now ignored on Outstanding Due`);
          this.refreshExclusionStatus();
        },
        error: (err: HttpErrorResponse) => {
          this.notificationService.showError(
            this.apiErrorMessage(err, 'Could not ignore customer. Try again.')
          );
        }
      });
  }

  restoreCurrentCustomer(): void {
    if (!this.currentExcludedKey) {
      return;
    }
    if (!this.canExcludeCustomer) {
      this.permissionService.notifyRoleDenied('restore ignored customers', 'customerExcludeEdit');
      return;
    }
    const name = this.getCustomerNameForMasterWrites();
    this.api.restoreExcludedCustomer(this.currentExcludedKey)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notificationService.showSuccess(`${name || this.currentExcludedKey} restored to Outstanding Due`);
          this.refreshExclusionStatus();
        },
        error: (err: HttpErrorResponse) => {
          this.notificationService.showError(
            this.apiErrorMessage(err, 'Could not restore customer.')
          );
        }
      });
  }

  retainCurrentCustomer(): void {
    const name = this.getCustomerNameForMasterWrites();
    if (!name) {
      return;
    }
    if (!this.canRetainCustomer) {
      this.permissionService.notifyRoleDenied('retain customers', 'customerRetainEdit');
      return;
    }
    this.api.retainCustomer(name)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notificationService.showSuccess(`${name} is now retained on Outstanding Due`);
          this.refreshExclusionStatus();
        },
        error: (err: HttpErrorResponse) => {
          this.notificationService.showError(
            this.apiErrorMessage(err, 'Could not retain customer. Try again.')
          );
        }
      });
  }

  unretainCurrentCustomer(): void {
    if (!this.currentRetainedKey) {
      return;
    }
    if (!this.canRetainCustomer) {
      this.permissionService.notifyRoleDenied('unretain customers', 'customerRetainEdit');
      return;
    }
    const name = this.getCustomerNameForMasterWrites();
    this.api.unretainCustomer(this.currentRetainedKey)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notificationService.showSuccess(`${name || this.currentRetainedKey} removed from retained`);
          this.refreshExclusionStatus();
        },
        error: (err: HttpErrorResponse) => {
          this.notificationService.showError(
            this.apiErrorMessage(err, 'Could not unretain customer.')
          );
        }
      });
  }

  setLedgerFilter(filter: 'paid' | 'unpaid' | 'all'): void {
    // Only update the filter - preserve all customer state (selection, summary, ledger, payment date, WhatsApp status)
    // DO NOT modify: selectedCustomerName, customerSummary, customerLedger, paymentDate, paymentDateEdit, whatsappStatus, whatsappStatusEdit
    this.ledgerFilter = filter;
  }

  clearLedger(): void {
    this.clearPendingMasterWriteTimers();
    this.customerQuery = '';
    this.customerSuggestions = [];
    this.phoneSuggestions = [];
    this.showSuggestions = false;
    this.highlightedIndex = -1;
    this.customerSummary = undefined;
    this.customerLedger = [];
    this.customerStatus = '';
    this.customerStatusIsError = false;
    this.ledgerFilter = 'all';
    this.selectedCustomerName = null;
    this.selectedPhoneNumber = null;
    this.paymentDate = null;
    this.paymentDateEdit = '';
    this.whatsappStatus = null;
    this.whatsappStatusEdit = 'not sent';
    this.customerCategory = null;
    this.customerCategoryEdit = 'A';
    this.customerNotes = [];
    this.notesExpanded = false;
    this.editingNoteId = null;
    this.editingNoteContent = '';
    this.newNoteContent = '';
    this.editingLocation = false;
    this.syncLocationFieldsFromSummary();
    this.resetExclusionStatus();
    this.resetRetentionStatus();
  }

  clearCustomerSelection(): void {
    this.clearLedger();
    // Clear localStorage and URL
    this.clearSavedCustomer();
    this.updateUrlWithoutCustomer();
  }

  private getCustomerFromQuery(): string | null {
    const query = window.location.search;
    if (!query) {
      return null;
    }
    const params = new URLSearchParams(query);
    const customer = params.get('customer');
    return customer ? customer.trim() : null;
  }

  private saveCustomer(name: string): void {
    // SECURITY: Store customer name in sessionStorage instead of localStorage
    // sessionStorage is cleared when browser tab closes, providing better security
    try {
      sessionStorage.setItem(this.selectedCustomerKey, name);
      // Also keep in localStorage for backward compatibility, but prefer sessionStorage
      localStorage.setItem(this.selectedCustomerKey, name);
    } catch (e) {
      // Ignore storage errors (e.g., in private browsing mode)
    }
  }

  private getSavedCustomer(): string | null {
    // SECURITY: Prefer sessionStorage over localStorage for better security
    try {
      // Try sessionStorage first
      const sessionSaved = sessionStorage.getItem(this.selectedCustomerKey);
      if (sessionSaved) {
        return sessionSaved.trim();
      }
      // Fallback to localStorage for backward compatibility
      const saved = localStorage.getItem(this.selectedCustomerKey);
      return saved ? saved.trim() : null;
    } catch (e) {
      return null;
    }
  }

  private clearSavedCustomer(): void {
    // SECURITY: Clear from both sessionStorage and localStorage
    try {
      sessionStorage.removeItem(this.selectedCustomerKey);
      localStorage.removeItem(this.selectedCustomerKey);
    } catch (e) {
      // Ignore storage errors
    }
  }

  private updateUrlWithCustomer(name: string): void {
    // SECURITY: Do NOT put sensitive data (customer names) in URL query parameters
    // Use sessionStorage instead - already handled by saveCustomer()
    // Only update URL to remove any existing customer param if present
    const currentUrlCustomer = this.getCustomerFromQuery();
    if (currentUrlCustomer) {
      // Remove customer from URL if present
      this.router.navigate([], {
        relativeTo: this.router.routerState.root,
        queryParams: { customer: null },
        queryParamsHandling: 'merge',
        replaceUrl: true
      });
    }
  }

  private updateUrlWithoutCustomer(): void {
    // SECURITY: Remove customer from URL if present
    this.router.navigate([], {
      relativeTo: this.router.routerState.root,
      queryParams: { customer: null },
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  filteredLedger(): CustomerLedgerEntry[] {
    if (this.ledgerFilter === 'paid') {
      return this.customerLedger.filter((row) => {
        const received = this.toAmount(row.receivedAmount);
        const due = this.toAmount(row.currentDue);
        return received > 0 || due === 0;
      });
    }
    if (this.ledgerFilter === 'unpaid') {
      return this.customerLedger.filter((row) => this.toAmount(row.currentDue) > 0);
    }
    return this.customerLedger;
  }

  getAverageAgeing(): number | null {
    const entries = this.filteredLedger();
    // Only consider ageing days for unpaid invoices (where currentDue > 0)
    const ageingDays = entries
      .filter((row) => !this.isPaid(row.currentDue))
      .map((row) => row.ageingDays)
      .filter((days): days is number => days != null && typeof days === 'number');
    
    if (ageingDays.length === 0) {
      return null;
    }
    
    const sum = ageingDays.reduce((acc, days) => acc + days, 0);
    return Math.round((sum / ageingDays.length) * 10) / 10; // Round to 1 decimal place
  }

  getTotalReceivedAmount(): number {
    const entries = this.filteredLedger();
    return entries.reduce((total, row) => total + this.toAmount(row.receivedAmount), 0);
  }

  getTotalCurrentDue(): number {
    const entries = this.filteredLedger();
    return entries.reduce((total, row) => total + this.toAmount(row.currentDue), 0);
  }

  getTotalInvoiceCount(): number {
    return this.filteredLedger().length;
  }

  getCustomerSince(): string {
    if (!this.customerLedger || this.customerLedger.length === 0) {
      return '-';
    }

    // Find the earliest invoice date
    const dates = this.customerLedger
      .map(row => row.invoiceDate)
      .filter(date => date != null && date.trim() !== '')
      .map(date => this.parseInvoiceDate(date!))
      .filter(date => date != null);

    if (dates.length === 0) {
      return '-';
    }

    const earliestDate = new Date(Math.min(...dates.map(d => d!.getTime())));
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    earliestDate.setHours(0, 0, 0, 0);

    // Calculate years and months
    let years = today.getFullYear() - earliestDate.getFullYear();
    let months = today.getMonth() - earliestDate.getMonth();

    // Adjust if current month is before the earliest month
    if (months < 0) {
      years--;
      months += 12;
    }

    // Adjust if current day is before the earliest day in the same month
    if (months === 0 && today.getDate() < earliestDate.getDate()) {
      years--;
      months = 11;
    }

    // Format: Standard business format (Y and M)
    if (years === 0 && months === 0) {
      return '0M';
    } else if (years === 0) {
      return `${months}M`;
    } else if (months === 0) {
      return `${years}Y`;
    } else {
      return `${years}Y ${months}M`;
    }
  }

  private parseInvoiceDate(dateString: string): Date | null {
    if (!dateString) {
      return null;
    }

    // Try to parse various date formats
    // Format: "21-Dec-2025 01:03 pm" or "21-Dec-2025"
    const cleaned = dateString.trim();
    
    // Try parsing with common formats
    let date = new Date(cleaned);
    if (!isNaN(date.getTime())) {
      return date;
    }

    // Try parsing "DD-MMM-YYYY" format
    const parts = cleaned.split(/[\s-]+/);
    if (parts.length >= 3) {
      const day = parseInt(parts[0], 10);
      const monthStr = parts[1];
      const year = parseInt(parts[2], 10);

      const monthMap: { [key: string]: number } = {
        'jan': 0, 'feb': 1, 'mar': 2, 'apr': 3, 'may': 4, 'jun': 5,
        'jul': 6, 'aug': 7, 'sep': 8, 'oct': 9, 'nov': 10, 'dec': 11
      };

      const month = monthMap[monthStr.toLowerCase().substring(0, 3)];
      if (month !== undefined && !isNaN(day) && !isNaN(year)) {
        date = new Date(year, month, day);
        if (!isNaN(date.getTime())) {
          return date;
        }
      }
    }

    return null;
  }

  getAverageAgeingColorClass(): string {
    const avg = this.getAverageAgeing();
    if (avg == null) return '';
    if (avg >= 1 && avg <= 45) return 'green';
    if (avg >= 46 && avg <= 85) return 'yellow';
    if (avg > 85) return 'red';
    return '';
  }

  toAmount(value: number | string | null | undefined): number {
    if (typeof value === 'number') {
      return value;
    }
    if (value == null) {
      return 0;
    }
    const cleaned = value.toString().replace(/[^0-9.\-]/g, '');
    if (!cleaned) {
      return 0;
    }
    const parsed = Number(cleaned);
    return Number.isNaN(parsed) ? 0 : parsed;
  }

  private setMessage(message: string): void {
    this.message = message;
    if (this.messageTimer) {
      window.clearTimeout(this.messageTimer);
    }
    this.messageTimer = window.setTimeout(() => {
      this.message = '';
    }, 30000);
  }

  getWhatsAppStatusDisplay(): string {
    if (!this.whatsappStatus || this.whatsappStatus === 'not sent') {
      return 'Not sent';
    }
    if (this.whatsappStatus === 'sent') {
      return 'Sent';
    }
    if (this.whatsappStatus === 'delivered') {
      return 'Delivered';
    }
    return 'Not sent';
  }

  getWhatsAppStatusBorderClass(): string {
    if (!this.whatsappStatus) {
      return 'border-grey';
    }
    if (this.whatsappStatus === 'sent') {
      return 'border-yellow';
    }
    if (this.whatsappStatus === 'delivered') {
      return 'border-green';
    }
    return 'border-grey';
  }

  getPaymentDateBorderClass(): string {
    return paymentDateBorderClass(paymentDateTone(this.paymentDate));
  }

  getAgeingDaysColorClass(ageingDays: number | null | undefined): string {
    if (ageingDays == null) return '';
    if (ageingDays >= 1 && ageingDays <= 45) return 'green';
    if (ageingDays >= 46 && ageingDays <= 85) return 'yellow';
    if (ageingDays > 85) return 'red';
    return '';
  }

  isPaid(currentDue: number | string | null | undefined): boolean {
    return this.toAmount(currentDue) <= 0.01;
  }

  onDateKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      (event.target as HTMLInputElement)?.blur();
    }
  }

  onPaymentDateInput(event: Event, input: HTMLInputElement): void {
    // Don't interfere if change is being processed
    if (this.isProcessingDateChange) {
      return;
    }
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    // Only handle text input (manual typing)
    if (!this.selectedCustomerName || input.type === 'date') {
      return;
    }
    
    const value = (event.target as HTMLInputElement).value;
    this.paymentDateEdit = value;
    
    const normalized = normalizeToDayMonth(value);
    
    // Update for immediate color change
    if (normalized) {
      if (isPaymentDatePast(normalized)) {
        this.paymentDateEdit = this.paymentDate ?? '';
        this.notificationService.showError('Payment date cannot be before today.', 4000);
        return;
      }
      this.paymentDate = normalized;
      if (this.customerSummary) {
        this.customerSummary = {
          ...this.customerSummary,
          nextPaymentDate: normalized
        };
      }
      this.schedulePaymentDateSave(normalized);
    }
  }

  openDatePicker(event: FocusEvent, input: HTMLInputElement): void {
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    if (!input || !this.selectedCustomerName || input.type === 'date') {
      return;
    }
    
    // Convert current DD-MM format to ISO for date input
    const current = this.paymentDateEdit ?? '';
    const iso = toIsoDate(current);
    
    // Switch to date input type
    input.type = 'date';
    input.min = todayIsoDate();
    if (iso) {
      input.value = iso;
    }
  }

  onDateChange(event: Event, input: HTMLInputElement): void {
    // Prevent default and bubbling
    event.preventDefault();
    event.stopPropagation();
    
    // Prevent re-entry
    if (this.isProcessingDateChange) {
      return;
    }
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    
    // Handle date selection from calendar
    if (input.type !== 'date' || !this.selectedCustomerName) {
      return;
    }
    
    const value = input.value;
    if (!value) {
      input.type = 'text';
      return;
    }
    
    // Convert ISO date (YYYY-MM-DD) to DD-MM format
    const normalized = normalizeToDayMonth(value);
    if (!normalized) {
      input.type = 'text';
      return;
    }
    if (isPaymentDatePast(normalized)) {
      input.type = 'text';
      input.value = this.paymentDate ?? '';
      this.paymentDateEdit = this.paymentDate ?? '';
      this.notificationService.showError('Payment date cannot be before today.', 4000);
      return;
    }
    
    // Set flag to prevent re-entry
    this.isProcessingDateChange = true;
    
    // Switch back to text input FIRST
    input.type = 'text';
    input.value = normalized;
    
    // Update component state
    this.paymentDateEdit = normalized;
    this.paymentDate = normalized;
    
    // Update customer summary if available
    if (this.customerSummary) {
      this.customerSummary = {
        ...this.customerSummary,
        nextPaymentDate: normalized
      };
    }
    
    // Save to backend
    this.savePaymentDate(normalized);
    
    // Trigger change detection
    this.cdr.detectChanges();
    
    // Reset flag after a short delay
    setTimeout(() => {
      this.isProcessingDateChange = false;
    }, 100);
  }

  onDateInputBlur(event: Event, input: HTMLInputElement): void {
    // Don't interfere if change is being processed
    if (this.isProcessingDateChange) {
      return;
    }
    // Just switch back to text if still in date mode
    if (input.type === 'date') {
      input.type = 'text';
    }
  }

  private schedulePaymentDateSave(value: string): void {
    if (this.paymentDateSaveTimer) {
      window.clearTimeout(this.paymentDateSaveTimer);
    }
    this.paymentDateSaveTimer = window.setTimeout(() => {
      this.savePaymentDate(value);
    }, PAYMENT_DATE_SAVE_DEBOUNCE_MS);
  }

  clearPaymentDate(): void {
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    this.paymentDateEdit = '';
    this.paymentDate = null;
    if (this.customerSummary) {
      this.customerSummary = {
        ...this.customerSummary,
        nextPaymentDate: null
      };
    }
    this.savePaymentDate('');
  }

  private savePaymentDate(value: string): void {
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
      this.notificationService.showError(
        'Customer details are still loading. Wait a moment and try again.',
        4000
      );
      return;
    }
    const cleaned = value.trim();
    if (!isValidPaymentDateFormat(cleaned)) {
      this.customerStatus = 'Invalid date format. Use dd-MM.';
      this.customerStatusIsError = true;
      this.notificationService.showError('Invalid date format. Use DD-MM.', 4000);
      return;
    }
    if (cleaned && isPaymentDatePast(cleaned)) {
      this.customerStatus = 'Payment date cannot be before today.';
      this.customerStatusIsError = true;
      this.notificationService.showError(this.customerStatus, 4000);
      this.paymentDateEdit = this.paymentDate ?? '';
      return;
    }
    this.customerStatus = '';
    this.customerStatusIsError = false;
    this.api.updateNextPaymentDate(customer, cleaned).subscribe({
      next: () => {
        this.paymentDate = cleaned || null;
        this.paymentDateEdit = cleaned;
        // Refresh customer summary to get latest data
        this.refreshCustomerSummary();
        this.notificationService.showSuccess(
          cleaned ? 'Payment date saved.' : 'Payment date cleared.',
          3000
        );
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
          return;
        }
        this.customerStatus = 'Unable to save due date.';
        this.customerStatusIsError = true;
        this.notificationService.showError('Failed to save due date.', 3000);
      }
    });
  }

  onWhatsAppRadioClick(e: MouseEvent): void {
    if (!this.canChangeWhatsappDate) {
      e.preventDefault();
      this.permissionService.notifyRoleDenied('change WhatsApp status', 'whatsappDateChange');
    }
  }

  onCategoryRadioClick(e: MouseEvent): void {
    if (!this.canEditCustomerCategory) {
      e.preventDefault();
      this.permissionService.notifyRoleDenied('edit customer category', 'customerCategoryEdit');
    }
  }

  onWhatsAppStatusChange(status: 'not sent' | 'sent' | 'delivered'): void {
    if (!this.canChangeWhatsappDate) {
      this.permissionService.notifyRoleDenied('change WhatsApp status', 'whatsappDateChange');
      this.whatsappStatusEdit = this.whatsappStatus ?? 'not sent';
      return;
    }
    // Only update the edit binding until save succeeds; whatsappStatus stays the last committed value.
    this.whatsappStatusEdit = status;
    this.scheduleWhatsAppStatusSave(status);
  }

  private scheduleWhatsAppStatusSave(status: 'not sent' | 'sent' | 'delivered'): void {
    if (this.whatsappStatusSaveTimer) {
      window.clearTimeout(this.whatsappStatusSaveTimer);
    }
    this.whatsappStatusSaveTimer = window.setTimeout(() => {
      this.saveWhatsAppStatus(status);
    }, 400);
  }

  private saveWhatsAppStatus(status: 'not sent' | 'sent' | 'delivered'): void {
    if (!this.canChangeWhatsappDate) {
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
      this.whatsappStatusEdit = this.whatsappStatus ?? 'not sent';
      this.notificationService.showError(
        'Customer details are still loading. Wait a moment and try again.',
        4000
      );
      return;
    }
    this.api.updateWhatsAppStatus(customer, status).subscribe({
      next: () => {
        this.whatsappStatus = status;
        // Update customer summary to keep it in sync
        if (this.customerSummary) {
          this.customerSummary = {
            ...this.customerSummary,
            whatsAppStatus: status
          };
        }
        this.notificationService.showSuccess('WhatsApp status saved.', 3000);
      },
      error: (err: HttpErrorResponse) => {
        this.whatsappStatusEdit = this.whatsappStatus ?? 'not sent';
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
          return;
        }
        this.customerStatus = 'Unable to save WhatsApp status.';
        this.customerStatusIsError = true;
        this.notificationService.showError('Failed to save WhatsApp status.', 3000);
      }
    });
  }

  onCustomerCategoryChange(category: 'semi-wholesale' | 'A' | 'B' | 'C'): void {
    if (!this.canEditCustomerCategory) {
      this.permissionService.notifyRoleDenied('edit customer category', 'customerCategoryEdit');
      this.customerCategoryEdit = this.customerCategory ?? 'A';
      return;
    }
    if (!this.getCustomerNameForMasterWrites()) {
      return;
    }
    this.customerCategoryEdit = category;
    this.customerCategory = category;
    this.scheduleCustomerCategorySave(category);
  }

  private scheduleCustomerCategorySave(category: 'semi-wholesale' | 'A' | 'B' | 'C'): void {
    if (this.customerCategorySaveTimer) {
      window.clearTimeout(this.customerCategorySaveTimer);
    }
    this.customerCategorySaveTimer = window.setTimeout(() => {
      this.saveCustomerCategory(category);
    }, 400);
  }

  private saveCustomerCategory(category: 'semi-wholesale' | 'A' | 'B' | 'C'): void {
    if (!this.canEditCustomerCategory) {
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
      return;
    }
    this.api.updateCustomerCategory(customer, category).subscribe({
      next: () => {
        // Category already updated in UI via onCustomerCategoryChange
        // Update customer summary to keep it in sync
        if (this.customerSummary) {
          this.customerSummary = {
            ...this.customerSummary,
            customerCategory: category
          };
        }
        this.notificationService.showSuccess('Customer category saved.', 3000);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
          return;
        }
        this.customerStatus = 'Unable to save customer category.';
        this.customerStatusIsError = true;
        this.notificationService.showError('Failed to save customer category.', 3000);
      }
    });
  }

  private syncCreditLimitFieldsFromSummary(summary: CustomerSummaryResponse): void {
    if (summary.creditLimitOverride != null) {
      this.creditLimitInput = String(summary.creditLimitOverride);
    } else {
      this.creditLimitInput = '';
    }
  }

  getCreditLimitSourceLabel(): string {
    const source = this.customerSummary?.creditLimitSource;
    if (source === 'override') {
      return 'Custom';
    }
    if (source === 'category') {
      const cat = this.customerSummary?.customerCategory ?? this.customerCategory;
      return cat ? `Category ${cat}` : 'Category';
    }
    return '';
  }

  getCreditLimitUtilizationPercent(): number | null {
    const util = this.customerSummary?.creditLimitUtilization;
    if (util == null) {
      return null;
    }
    return Math.round(util * 100);
  }

  onCreditLimitInput(): void {
    if (!this.canEditCustomerLimit) {
      return;
    }
    if (this.creditLimitSaveTimer) {
      window.clearTimeout(this.creditLimitSaveTimer);
    }
    this.creditLimitSaveTimer = window.setTimeout(() => {
      this.saveCreditLimitOverride();
    }, 600);
  }

  clearCreditLimitOverride(): void {
    if (!this.canEditCustomerLimit) {
      this.permissionService.notifyRoleDenied('edit customer credit limit', 'customerLimitEdit');
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
      return;
    }
    this.creditLimitInput = '';
    this.api.updateCustomerCreditLimit(customer, null).subscribe({
      next: () => {
        this.reloadCustomerSummaryForCreditLimit(customer);
        this.notificationService.showSuccess('Using category credit limit.', 3000);
      },
      error: () => {
        this.notificationService.showError('Failed to clear credit limit override.', 3000);
      }
    });
  }

  private saveCreditLimitOverride(): void {
    if (!this.canEditCustomerLimit) {
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
      return;
    }
    const trimmed = this.creditLimitInput.trim().replace(/,/g, '');
    if (!trimmed) {
      return;
    }
    const parsed = Number(trimmed);
    if (!Number.isFinite(parsed) || parsed < 0) {
      this.notificationService.showError('Enter a valid credit limit amount.', 3000);
      return;
    }
    this.api.updateCustomerCreditLimit(customer, parsed).subscribe({
      next: () => {
        this.reloadCustomerSummaryForCreditLimit(customer);
        this.notificationService.showSuccess('Credit limit saved.', 3000);
      },
      error: () => {
        this.notificationService.showError('Failed to save credit limit.', 3000);
      }
    });
  }

  private reloadCustomerSummaryForCreditLimit(customer: string): void {
    this.api.getCustomerSummary(customer).subscribe({
      next: (summary) => {
        this.customerSummary = summary;
        this.syncCreditLimitFieldsFromSummary(summary);
      }
    });
  }

  getCustomerCategoryBorderClass(): string {
    const category = this.customerCategoryEdit || this.customerCategory;
    if (!category) {
      return '';
    }
    switch (category) {
      case 'semi-wholesale':
        return 'border-blue';
      case 'A':
        return 'border-green';
      case 'B':
        return 'border-yellow';
      case 'C':
        return 'border-red';
      default:
        return '';
    }
  }

  onFollowUpToggle(): void {
    if (!this.canChangeFollowUp) {
      this.permissionService.notifyRoleDenied('change follow-up flags', 'followUpChange');
      return;
    }
    if (!this.getCustomerNameForMasterWrites()) {
      return;
    }
    this.followUpFlagEdit = !this.followUpFlagEdit;
    this.scheduleFollowUpSave(this.followUpFlagEdit);
  }

  private scheduleFollowUpSave(needsFollowUp: boolean): void {
    if (this.followUpSaveTimer) {
      window.clearTimeout(this.followUpSaveTimer);
    }
    this.followUpSaveTimer = window.setTimeout(() => {
      this.saveFollowUpFlag(needsFollowUp);
    }, 400);
  }

  private saveFollowUpFlag(needsFollowUp: boolean): void {
    if (!this.canChangeFollowUp) {
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
      return;
    }
    this.api.updateFollowUpFlag(customer, needsFollowUp).subscribe({
      next: () => {
        this.followUpFlag = needsFollowUp;
        // Update customer summary to keep it in sync
        if (this.customerSummary) {
          this.customerSummary = {
            ...this.customerSummary,
            needsFollowUp: needsFollowUp
          };
        }
        this.notificationService.showSuccess(
          needsFollowUp ? 'Follow-up marked as needed.' : 'Follow-up cleared.',
          3000
        );
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
          return;
        }
        this.customerStatus = 'Unable to save follow-up flag.';
        this.customerStatusIsError = true;
        this.notificationService.showError('Failed to save follow-up.', 3000);
        // Revert the change on error
        this.followUpFlagEdit = !needsFollowUp;
        this.followUpFlag = !needsFollowUp;
      }
    });
  }

  editLocation(): void {
    if (!this.canEditCustomerLocation) {
      this.permissionService.notifyRoleDenied('edit customer location', 'customerLocationEdit');
      return;
    }
    if (!this.getCustomerNameForMasterWrites()) {
      return;
    }
    this.syncLocationFieldsFromSummary();
    this.editingLocation = true;
  }

  cancelLocationEdit(): void {
    this.editingLocation = false;
    this.syncLocationFieldsFromSummary();
  }

  private syncLocationFieldsFromSummary(summary?: CustomerSummaryResponse): void {
    const source = summary ?? this.customerSummary;
    if (!source) {
      this.locationAddress = '';
      this.locationLatitude = null;
      this.locationLongitude = null;
      return;
    }
    this.locationAddress = source.address || '';
    this.locationLatitude = source.latitude ?? null;
    this.locationLongitude = source.longitude ?? null;
  }

  saveLocation(locationData: LocationData): void {
    if (!this.canEditCustomerLocation) {
      this.permissionService.notifyRoleDenied('edit customer location', 'customerLocationEdit');
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
      this.notificationService.showError(
        'Customer details are still loading. Wait a moment and try again.',
        4000
      );
      return;
    }
    this.customerStatus = '';
    this.customerStatusIsError = false;
    const latitude = locationData.latitude === 0 ? null : locationData.latitude;
    const longitude = locationData.longitude === 0 ? null : locationData.longitude;
    const address = locationData.address?.trim() || null;
    this.api.updateCustomerLocation(customer, {
      address,
      latitude,
      longitude
    }).subscribe({
      next: () => {
        this.locationAddress = address || '';
        this.locationLatitude = latitude;
        this.locationLongitude = longitude;
        this.editingLocation = false;
        if (this.customerSummary) {
          this.customerSummary = {
            ...this.customerSummary,
            address: address ?? undefined,
            latitude: latitude ?? undefined,
            longitude: longitude ?? undefined
          };
        }
        this.refreshCustomerSummary();
        this.notificationService.showSuccess('Location saved successfully', 3000);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
          return;
        }
        this.customerStatus = 'Unable to save location.';
        this.customerStatusIsError = true;
        this.notificationService.showError('Failed to save location', 3000);
      }
    });
  }

  copyAddress(): void {
    if (this.customerSummary?.address) {
      navigator.clipboard.writeText(this.customerSummary.address).then(() => {
        this.notificationService.showSuccess('Address copied to clipboard', 2000);
      }).catch(() => {
        this.notificationService.showError('Failed to copy address', 2000);
      });
    }
  }

  getCustomerCoordinatesDms(): string | null {
    const lat = this.customerSummary?.latitude;
    const lng = this.customerSummary?.longitude;
    if (lat == null || lng == null) {
      return null;
    }
    return formatCoordinatesDms(lat, lng);
  }

  copyCoordinates(): void {
    const coordinates = this.getCustomerCoordinatesDms();
    if (!coordinates) {
      return;
    }
    navigator.clipboard.writeText(coordinates).then(() => {
      this.notificationService.showSuccess('Coordinates copied to clipboard', 2000);
    }).catch(() => {
      this.notificationService.showError('Failed to copy coordinates', 2000);
    });
  }

  toggleCoordinates(): void {
    this.coordinatesExpanded = !this.coordinatesExpanded;
  }

  deleteLocation(): void {
    if (!this.canEditCustomerLocation) {
      this.permissionService.notifyRoleDenied('edit customer location', 'customerLocationEdit');
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
      this.notificationService.showError(
        'Customer details are still loading. Wait a moment and try again.',
        4000
      );
      return;
    }
    
    if (!confirm('Are you sure you want to delete this location?')) {
      return;
    }

    this.customerStatus = '';
    this.customerStatusIsError = false;
    this.api.updateCustomerLocation(customer, {
      address: '',
      latitude: null,
      longitude: null
    }).subscribe({
      next: () => {
        this.locationAddress = '';
        this.locationLatitude = null;
        this.locationLongitude = null;
        this.destroyLocationMap();
        if (this.customerSummary) {
          this.customerSummary = {
            ...this.customerSummary,
            address: undefined,
            latitude: undefined,
            longitude: undefined
          };
        }
        this.refreshCustomerSummary();
        this.notificationService.showSuccess('Location deleted successfully', 3000);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
          return;
        }
        this.customerStatus = 'Unable to delete location.';
        this.customerStatusIsError = true;
        this.notificationService.showError('Failed to delete location', 3000);
      }
    });
  }

  private destroyLocationMap(): void {
    if (this.locationMap) {
      this.locationMap.remove();
      this.locationMap = null;
      this.locationMarker = null;
    }
    this.mapInitialized = false;
  }

  initLocationMap(): void {
    if (!this.locationMapPreview?.nativeElement || !this.customerSummary?.latitude || !this.customerSummary?.longitude) {
      return;
    }

    this.destroyLocationMap();

    const lat = this.customerSummary.latitude;
    const lng = this.customerSummary.longitude;

    this.locationMap = L.map(this.locationMapPreview.nativeElement).setView([lat, lng], 15);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap contributors',
      maxZoom: 19
    }).addTo(this.locationMap);

    this.locationMarker = L.marker([lat, lng]).addTo(this.locationMap);
    this.mapInitialized = true;

    // Panel height animates 0 → 150px — resize map after layout settles
    setTimeout(() => {
      this.locationMap?.invalidateSize();
    }, 350);
  }

  toggleAddress(): void {
    this.addressExpanded = !this.addressExpanded;
  }

  toggleMap(): void {
    this.mapExpanded = !this.mapExpanded;
    if (!this.mapExpanded) {
      this.destroyLocationMap();
      return;
    }

    if (this.customerSummary?.latitude && this.customerSummary?.longitude) {
      setTimeout(() => this.initLocationMap(), 50);
    }
  }

  private refreshCustomerSummary(): void {
    const customerName = this.getCustomerNameForMasterWrites();
    if (!customerName) {
      return;
    }
    this.api.getCustomerSummary(customerName).subscribe({
      next: (summary) => {
        this.customerSummary = summary;
        this.applyCanonicalCustomerNameFromSummary(summary);
        if (summary.nextPaymentDate) {
          this.paymentDate = summary.nextPaymentDate;
          this.paymentDateEdit = summary.nextPaymentDate;
        } else {
          this.paymentDate = null;
          this.paymentDateEdit = '';
        }
        const status = summary.whatsAppStatus;
        this.whatsappStatus = (status && status.trim() !== '') ? status as 'not sent' | 'sent' | 'delivered' : 'not sent';
        this.whatsappStatusEdit = this.whatsappStatus;
        this.followUpFlag = summary.needsFollowUp ?? false;
        this.followUpFlagEdit = this.followUpFlag;
        this.syncCreditLimitFieldsFromSummary(summary);
        this.syncLocationFieldsFromSummary(summary);
        this.destroyLocationMap();
        if (this.mapExpanded && summary.latitude && summary.longitude) {
          setTimeout(() => this.initLocationMap(), 50);
        }
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.customerStatus = 'Session expired. Please login again.';
          this.customerStatusIsError = true;
          this.logout();
        }
      }
    });
  }

  downloadExcel(): void {
    if (!this.canDownloadWholeProject) {
      this.notificationService.showPermissionError();
      return;
    }
    if (!this.selectedCustomerName || !this.customerSummary?.found) {
      return;
    }

    const filteredData = this.filteredLedger();
    if (filteredData.length === 0) {
      return;
    }

    // Prepare customer details and receivable ageing at the top
    const customerDetails = [
      ['Customer Name', this.selectedCustomerName],
      ['Phone Number', formatPhoneDisplay(this.customerSummary.phoneNumber) || 'Not available'],
      ['']
    ];
    const receivableAgeing = [
      ['Receivable Ageing (latest upload)'],
      ['Total Outstanding', formatInrForExcel(this.customerSummary?.totalAmount ?? 0)],
      ['1-45 Days', formatInrForExcel(this.customerSummary?.withinAmount ?? 0)],
      ['46-85 Days', formatInrForExcel(this.customerSummary?.midAmount ?? 0)],
      ['>85 Days', formatInrForExcel(this.customerSummary?.beyondAmount ?? 0)],
      [''],
      ['Invoice Ledger (detailed sales — ageing from invoice dates)'],
      ['']
    ];

    // Prepare table headers based on filter
    let headers: string[] = ['Invoice Date', 'Voucher No.', 'Ageing Days'];
    if (this.ledgerFilter === 'paid') {
      headers.push('Received Amount');
    }
    if (this.ledgerFilter === 'unpaid') {
      headers.push('Current Due');
    }
    if (this.ledgerFilter === 'all') {
      headers.push('Received Amount', 'Current Due');
    }

    // Prepare summary row with values below headers
    const summaryRow: any[] = [
      this.getCustomerSince(), // Below Invoice Date
      `${this.getTotalInvoiceCount()} invoices`, // Below Voucher No.
      this.getAverageAgeing() != null ? `${this.getAverageAgeing()} days` : '-' // Below Ageing Days
    ];
    
    if (this.ledgerFilter === 'paid') {
      summaryRow.push(formatInrForExcel(this.getTotalReceivedAmount()));
    }
    if (this.ledgerFilter === 'unpaid') {
      summaryRow.push(formatInrForExcel(this.getTotalCurrentDue()));
    }
    if (this.ledgerFilter === 'all') {
      summaryRow.push(formatInrForExcel(this.getTotalReceivedAmount()));
      summaryRow.push(formatInrForExcel(this.getTotalCurrentDue()));
    }

    // Prepare table data
    const tableData = filteredData.map(row => {
      const rowData: any[] = [
        row.invoiceDate || '-',
        row.voucherNo || '-',
        row.ageingDays != null ? row.ageingDays : '-'
      ];
      
      if (this.ledgerFilter === 'paid') {
        rowData.push(formatInrForExcel(this.toAmount(row.receivedAmount)));
      }
      if (this.ledgerFilter === 'unpaid') {
        rowData.push(formatInrForExcel(this.toAmount(row.currentDue)));
      }
      if (this.ledgerFilter === 'all') {
        rowData.push(formatInrForExcel(this.toAmount(row.receivedAmount)));
        rowData.push(formatInrForExcel(this.toAmount(row.currentDue)));
      }
      
      return rowData;
    });

    // Create workbook
    const wb = XLSX.utils.book_new();
    
    // Add watermark row at the top
    const totalCols = headers.length;
    const watermarkRow = buildExcelWatermarkRow(totalCols);
    
    // Combine watermark, customer details, headers, summary row, and ledger table
    const allData = [watermarkRow, ...customerDetails, ...receivableAgeing, headers, summaryRow, ...tableData];
    const ws = XLSX.utils.aoa_to_sheet(allData);
    
    // Set column widths for better readability
    const colWidths = [
      { wch: 20 }, // Invoice Date
      { wch: 25 }, // Voucher No.
      { wch: 12 }, // Ageing Days
    ];
    if (this.ledgerFilter === 'paid') {
      colWidths.push({ wch: 18 }); // Received Amount
    }
    if (this.ledgerFilter === 'unpaid') {
      colWidths.push({ wch: 18 }); // Current Due
    }
    if (this.ledgerFilter === 'all') {
      colWidths.push({ wch: 18 }, { wch: 18 }); // Received Amount, Current Due
    }
    ws['!cols'] = colWidths;
    
    // Merge watermark row cells
    if (!ws['!merges']) {
      ws['!merges'] = [];
    }
    ws['!merges'].push({ s: { r: 0, c: 0 }, e: { r: 0, c: totalCols - 1 } });

    XLSX.utils.book_append_sheet(wb, ws, 'Customer Details');
    setExcelPrintTitleTopRow(wb, 'Customer Details');

    // Generate filename
    const filename = `${this.selectedCustomerName}_${this.ledgerFilter}.xlsx`;
    
    // Download
    XLSX.writeFile(wb, filename);
  }

  downloadPDF(): void {
    void this.downloadLedgerPdf();
  }

  private async downloadLedgerPdf(): Promise<void> {
    if (!this.canDownloadWholeProject) {
      this.notificationService.showPermissionError();
      return;
    }
    if (!this.selectedCustomerName || !this.customerSummary?.found) {
      return;
    }

    const filteredData = this.filteredLedger();
    if (filteredData.length === 0) {
      return;
    }

    const doc = new jsPDF();
    try {
      await ensurePdfUnicodeFonts(doc);
    } catch {
      this.notificationService.showError('Could not load PDF fonts. Refresh the page and try again.');
      return;
    }

    let yPos = 20;

    // Customer Details at the top
    doc.setFontSize(11);
    doc.setFont(PDF_UNICODE_FONT, 'normal');
    doc.text(`Customer Name: ${this.selectedCustomerName}`, 14, yPos);
    yPos += 7;
    doc.text(`Phone Number: ${formatPhoneDisplay(this.customerSummary.phoneNumber) || 'Not available'}`, 14, yPos);
    yPos += 10;

    doc.setFontSize(10);
    doc.text('Receivable Ageing (latest upload)', 14, yPos);
    yPos += 6;
    doc.text(`Total Outstanding: ${formatInrForPdf(this.customerSummary?.totalAmount ?? 0)}`, 14, yPos);
    yPos += 5;
    doc.text(`1-45 Days: ${formatInrForPdf(this.customerSummary?.withinAmount ?? 0)}`, 14, yPos);
    yPos += 5;
    doc.text(`46-85 Days: ${formatInrForPdf(this.customerSummary?.midAmount ?? 0)}`, 14, yPos);
    yPos += 5;
    doc.text(`>85 Days: ${formatInrForPdf(this.customerSummary?.beyondAmount ?? 0)}`, 14, yPos);
    yPos += 10;

    doc.setFontSize(10);
    doc.text('Invoice Ledger (detailed sales — ageing from invoice dates)', 14, yPos);
    yPos += 8;

    // Prepare table data
    let headers: string[] = ['Invoice Date', 'Voucher No.', 'Ageing Days'];
    if (this.ledgerFilter === 'paid') {
      headers.push('Received Amount');
    }
    if (this.ledgerFilter === 'unpaid') {
      headers.push('Current Due');
    }
    if (this.ledgerFilter === 'all') {
      headers.push('Received Amount', 'Current Due');
    }

    // Prepare summary row with values below headers
    const summaryRow: any[] = [
      this.getCustomerSince(), // Below Invoice Date
      `${this.getTotalInvoiceCount()} invoices`, // Below Voucher No.
      this.getAverageAgeing() != null ? `${this.getAverageAgeing()} days` : '-' // Below Ageing Days
    ];
    
    if (this.ledgerFilter === 'paid') {
      summaryRow.push(formatInrForPdf(this.getTotalReceivedAmount()));
    }
    if (this.ledgerFilter === 'unpaid') {
      summaryRow.push(formatInrForPdf(this.getTotalCurrentDue()));
    }
    if (this.ledgerFilter === 'all') {
      summaryRow.push(formatInrForPdf(this.getTotalReceivedAmount()));
      summaryRow.push(formatInrForPdf(this.getTotalCurrentDue()));
    }

    const tableData = filteredData.map(row => {
      const rowData: any[] = [
        row.invoiceDate || '-',
        row.voucherNo || '-',
        row.ageingDays != null ? row.ageingDays.toString() : '-'
      ];
      
      if (this.ledgerFilter === 'paid') {
        rowData.push(formatInrForPdf(this.toAmount(row.receivedAmount)));
      }
      if (this.ledgerFilter === 'unpaid') {
        rowData.push(formatInrForPdf(this.toAmount(row.currentDue)));
      }
      if (this.ledgerFilter === 'all') {
        rowData.push(formatInrForPdf(this.toAmount(row.receivedAmount)));
        rowData.push(formatInrForPdf(this.toAmount(row.currentDue)));
      }
      
      return rowData;
    });

    // Add table with summary row
    autoTable(doc, {
      head: [headers],
      body: [summaryRow, ...tableData],
      startY: yPos,
      styles: { font: PDF_UNICODE_FONT, fontStyle: 'normal', fontSize: 9 },
      headStyles: {
        fillColor: [66, 139, 202],
        textColor: 255,
        fontStyle: 'bold',
        font: PDF_UNICODE_FONT,
      },
      bodyStyles: { fillColor: false },
      alternateRowStyles: { fillColor: [245, 245, 245] },
      didParseCell: (data: any) => {
        // Style the summary row (first data row)
        if (data.row.index === 0 && data.row.section === 'body') {
          data.cell.styles.fontStyle = 'normal';
          data.cell.styles.fillColor = [248, 250, 252];
          data.cell.styles.textColor = [0, 0, 0];
        }
      },
      didDrawPage: () => {
        addWatermark(doc);
      },
    });

    // Generate filename
    const filename = `${this.selectedCustomerName}_${this.ledgerFilter}.pdf`;
    
    // Download
    doc.save(filename);
  }

  // Customer Notes Methods
  loadNotes(): void {
    if (!this.canViewCustomerNotes) {
      this.customerNotes = [];
      return;
    }
    if (!this.getCustomerNameForMasterWrites() && !this.selectedPhoneNumber) {
      this.customerNotes = [];
      return;
    }

    this.isLoadingNotes = true;
    this.api.getCustomerNotes({
      customerName: this.getCustomerNameForMasterWrites() || null,
      phoneNumber: this.selectedPhoneNumber || null
    }).subscribe({
      next: (notes) => {
        this.customerNotes = notes;
        this.isLoadingNotes = false;
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.logout();
          return;
        }
        this.isLoadingNotes = false;
        this.notificationService.showError('Unable to load notes.');
      }
    });
  }

  toggleNotes(): void {
    this.notesExpanded = !this.notesExpanded;
    if (this.notesExpanded && this.customerNotes.length === 0) {
      this.loadNotes();
    }
  }

  startEditingNote(note: CustomerNote): void {
    if (!this.canEditCustomerNotes) {
      this.permissionService.notifyRoleDenied('edit customer notes', 'customerNotesEdit');
      return;
    }
    this.editingNoteId = note.id;
    this.editingNoteContent = note.note;
  }

  cancelEditingNote(): void {
    this.editingNoteId = null;
    this.editingNoteContent = '';
  }

  saveNote(): void {
    if (!this.canEditCustomerNotes) {
      this.permissionService.notifyRoleDenied('edit customer notes', 'customerNotesEdit');
      return;
    }
    if (!this.editingNoteId || !this.editingNoteContent.trim()) {
      return;
    }

    this.api.updateCustomerNote({
      noteId: this.editingNoteId,
      note: this.editingNoteContent.trim()
    }).subscribe({
      next: () => {
        this.loadNotes();
        this.cancelEditingNote();
        this.notificationService.showSuccess('Note updated successfully.');
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.logout();
          return;
        }
        this.notificationService.showError('Unable to update note.');
      }
    });
  }

  deleteNote(noteId: string): void {
    if (!this.canEditCustomerNotes) {
      this.permissionService.notifyRoleDenied('edit customer notes', 'customerNotesEdit');
      return;
    }
    if (!confirm('Are you sure you want to delete this note?')) {
      return;
    }

    this.api.deleteCustomerNote({ noteId }).subscribe({
      next: () => {
        this.loadNotes();
        this.notificationService.showSuccess('Note deleted successfully.');
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.logout();
          return;
        }
        this.notificationService.showError('Unable to delete note.');
      }
    });
  }

  addNote(): void {
    if (!this.canEditCustomerNotes) {
      this.permissionService.notifyRoleDenied('edit customer notes', 'customerNotesEdit');
      return;
    }
    if (!this.newNoteContent.trim()) {
      return;
    }
    if (this.customerNotes.length >= this.maxCustomerNotes) {
      this.notificationService.showError('Maximum 6 notes. Delete one to add another.');
      return;
    }

    if (!this.getCustomerNameForMasterWrites() && !this.selectedPhoneNumber) {
      this.notificationService.showError('Please select a customer first.');
      return;
    }

    this.api.createCustomerNote({
      customerName: this.getCustomerNameForMasterWrites() || null,
      phoneNumber: this.selectedPhoneNumber || null,
      note: this.newNoteContent.trim()
    }).subscribe({
      next: () => {
        this.newNoteContent = '';
        this.loadNotes();
        this.notificationService.showSuccess('Note added successfully.');
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.logout();
          return;
        }
        if (err.status === 400 && this.customerNotes.length >= this.maxCustomerNotes) {
          this.notificationService.showError('Maximum 6 notes. Delete one to add another.');
          return;
        }
        this.notificationService.showError('Unable to add note.');
      }
    });
  }

  formatDate(dateString: string): string {
    try {
      const date = new Date(dateString);
      return date.toLocaleDateString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      });
    } catch {
      return dateString;
    }
  }

  callCustomer(phoneNumber: string | null | undefined): void {
    if (!phoneNumber || phoneNumber.trim() === '') {
      return;
    }

    const cleanPhone = formatPhoneForTel(phoneNumber);
    this.copyPhoneNumber(phoneNumber, false);
    window.location.href = `tel:${cleanPhone}`;
  }

  copyPhoneNumber(phoneNumber: string | null | undefined, showNotification: boolean = true): void {
    if (!phoneNumber || phoneNumber.trim() === '') {
      if (showNotification) {
        this.notificationService.showError('No phone number available to copy.');
      }
      return;
    }

    const cleanPhone = formatPhoneDisplay(phoneNumber);
    if (!cleanPhone) {
      return;
    }
    
    // Use Clipboard API if available
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(cleanPhone).then(() => {
        if (showNotification) {
          this.notificationService.showSuccess('Phone number copied to clipboard!');
        }
      }).catch(() => {
        // Fallback to older method
        this.fallbackCopyPhoneNumber(cleanPhone, showNotification);
      });
    } else {
      // Fallback for older browsers
      this.fallbackCopyPhoneNumber(cleanPhone, showNotification);
    }
  }

  private fallbackCopyPhoneNumber(phoneNumber: string, showNotification: boolean): void {
    // Create a temporary textarea element
    const textarea = document.createElement('textarea');
    textarea.value = phoneNumber;
    textarea.style.position = 'fixed';
    textarea.style.left = '-999999px';
    textarea.style.top = '-999999px';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    
    try {
      const successful = document.execCommand('copy');
      if (successful && showNotification) {
        this.notificationService.showSuccess('Phone number copied to clipboard!');
      } else if (!successful && showNotification) {
        this.notificationService.showError('Failed to copy phone number.');
      }
    } catch (err) {
      if (showNotification) {
        this.notificationService.showError('Failed to copy phone number.');
      }
    } finally {
      document.body.removeChild(textarea);
    }
  }

  ngOnDestroy(): void {
    // Clear all timers
    if (this.customerTimer) {
      window.clearTimeout(this.customerTimer);
    }
    if (this.phoneTimer) {
      window.clearTimeout(this.phoneTimer);
    }
    if (this.messageTimer) {
      window.clearTimeout(this.messageTimer);
    }
    this.clearPendingMasterWriteTimers();
    this.destroyLocationMap();
    // Complete destroy subject to cleanup subscriptions
    this.destroy$.next();
    this.destroy$.complete();
  }
}


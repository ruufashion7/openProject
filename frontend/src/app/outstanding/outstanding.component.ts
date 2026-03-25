import { Component, OnInit, OnDestroy, ChangeDetectorRef, ViewChild, ElementRef, AfterViewChecked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';
import { ApiService, CustomerLedgerEntry, CustomerSummaryResponse, CustomerNote } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { LocationInputComponent, LocationData } from '../shared/location-input/location-input.component';
import { HttpErrorResponse } from '@angular/common/http';
import * as L from 'leaflet';
import * as XLSX from 'xlsx';
import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';
import {
  addWatermark,
  buildExcelWatermarkRow,
  setExcelPrintTitleTopRow,
} from '../shared/export-watermark';
import { formatInrForExcel, formatInrForPdf } from '../shared/format-inr-export';
import { ensurePdfUnicodeFonts, PDF_UNICODE_FONT } from '../shared/pdf-unicode-font';

@Component({
  selector: 'app-outstanding',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, LocationInputComponent],
  templateUrl: './outstanding.component.html',
  styleUrl: './outstanding.component.css'
})
export class OutstandingComponent implements OnInit, OnDestroy, AfterViewChecked {
  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';
  ready = false;
  customerQuery = '';
  customerSuggestions: string[] = [];
  phoneSuggestions: string[] = [];
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
  mapExpanded: boolean = false;
  private customerTimer?: number;
  private phoneTimer?: number;
  private messageTimer?: number;
  private paymentDateSaveTimer?: number;
  private whatsappStatusSaveTimer?: number;
  private customerCategorySaveTimer?: number;
  private followUpSaveTimer?: number;
  private isProcessingDateChange = false;
  private readonly selectedCustomerKey = 'openProject.selectedCustomer';
  canDownloadWholeProject = false;
  canEditPaymentDate = false;
  canChangeWhatsappDate = false;
  canChangeFollowUp = false;
  /** Customer master fields (require Details or Outstanding + specific permission). */
  canEditCustomerCategory = false;
  canViewCustomerNotes = false;
  canEditCustomerNotes = false;
  canEditCustomerLocation = false;
  
  // Subscription management
  private destroy$ = new Subject<void>();
  // Customer Notes
  customerNotes: CustomerNote[] = [];
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
    private notificationService: NotificationService
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

  private applyPermissionFlags(): void {
    this.canDownloadWholeProject = this.permissionService.canDownloadWholeProject();
    this.canEditPaymentDate = this.permissionService.canEditPaymentDate();
    this.canChangeWhatsappDate = this.permissionService.canChangeWhatsappDate();
    this.canChangeFollowUp = this.permissionService.canChangeFollowUp();
    this.canEditCustomerCategory = this.permissionService.canEditCustomerCategory();
    this.canViewCustomerNotes = this.permissionService.canViewCustomerNotes();
    this.canEditCustomerNotes = this.permissionService.canEditCustomerNotes();
    this.canEditCustomerLocation = this.permissionService.canEditCustomerLocation();
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
    // Summary is cleared while the next /customer-summary request is in flight; using only
    // selectedCustomerName here would send the wrong customer key (canonical name mismatch).
    if (this.customerSummary === undefined) {
      return null;
    }
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
    
    // Search for both customer names and phone numbers
    let customerSuggestionsReceived = false;
    let phoneSuggestionsReceived = false;
    
    const checkAndUpdateStatus = () => {
      if (customerSuggestionsReceived && phoneSuggestionsReceived) {
        // Remove duplicate phone numbers that already appear in customer suggestions
        const customerSet = new Set(this.customerSuggestions.map(c => c.toLowerCase().trim()));
        this.phoneSuggestions = this.phoneSuggestions.filter(phone => {
          const phoneTrimmed = phone.trim();
          // If phone number matches a customer name exactly, exclude it from phone suggestions
          return !customerSet.has(phoneTrimmed.toLowerCase());
        });
        
        const totalSuggestions = this.customerSuggestions.length + this.phoneSuggestions.length;
        this.showSuggestions = totalSuggestions > 0;
        this.customerStatus = totalSuggestions ? '' : 'No results found.';
        this.customerStatusIsError = totalSuggestions === 0;
      }
    };
    
    // Search customer names
    this.customerTimer = window.setTimeout(() => {
      this.api.getCustomerSuggestions(query, 20).subscribe({
        next: (suggestions) => {
          this.customerSuggestions = suggestions;
          customerSuggestionsReceived = true;
          checkAndUpdateStatus();
        },
        error: (err: HttpErrorResponse) => {
          this.customerSuggestions = [];
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
    } else {
      this.selectedCustomerName = name;
      this.selectedPhoneNumber = null;
    }
    
    this.customerSummary = undefined;
    this.customerLedger = [];
    this.paymentDate = null;
    this.whatsappStatus = null;
    
    // Save to localStorage and update URL only if different and not a phone number
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
          this.customerStatus = 'No data found for this customer.';
          this.customerStatusIsError = true; // Make "not found" messages red for consistency
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
        this.applyCanonicalCustomerNameFromSummary(summary);
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
        this.applyCanonicalCustomerNameFromSummary(summary);
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
    const tone = this.getPaymentDateTone();
    switch (tone) {
      case 'red':
        return 'border-red';
      case 'yellow':
        return 'border-yellow';
      case 'green':
        return 'border-green';
      case 'neutral':
      default:
        return 'border-grey';
    }
  }

  getPaymentDateTone(): 'neutral' | 'yellow' | 'green' | 'red' {
    if (!this.paymentDate) {
      return 'neutral';
    }
    const value = this.paymentDate.trim();
    if (!value) {
      return 'neutral';
    }
    const match = value.match(/^(\d{2})-(\d{2})$/);
    if (!match) {
      return 'neutral';
    }
    const day = Number(match[1]);
    const month = Number(match[2]);
    if (!day || !month || day > 31 || month > 12) {
      return 'neutral';
    }
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const target = new Date(now.getFullYear(), month - 1, day);
    if (Number.isNaN(target.getTime())) {
      return 'neutral';
    }
    const todayTime = today.getTime();
    const targetTime = target.getTime();
    if (targetTime === todayTime) {
      return 'yellow';
    }
    if (targetTime > todayTime) {
      return 'green';
    }
    return 'red';
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
      this.permissionService.notifyRoleDenied('edit payment dates', 'paymentDateEdit');
      return;
    }
    // Only handle text input (manual typing)
    if (!this.selectedCustomerName || input.type === 'date') {
      return;
    }
    
    const value = (event.target as HTMLInputElement).value;
    this.paymentDateEdit = value;
    
    const normalized = this.normalizeToDayMonth(value);
    
    // Update for immediate color change
    if (normalized) {
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
      this.permissionService.notifyRoleDenied('edit payment dates', 'paymentDateEdit');
      return;
    }
    if (!input || !this.selectedCustomerName || input.type === 'date') {
      return;
    }
    
    // Convert current DD-MM format to ISO for date input
    const current = this.paymentDateEdit ?? '';
    const iso = this.toIsoDate(current);
    
    // Switch to date input type
    input.type = 'date';
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
      this.permissionService.notifyRoleDenied('edit payment dates', 'paymentDateEdit');
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
    const normalized = this.normalizeToDayMonth(value);
    if (!normalized) {
      input.type = 'text';
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

  private toIsoDate(value: string): string | null {
    const trimmed = value.trim();
    if (!/^\d{2}-\d{2}$/.test(trimmed)) {
      return null;
    }
    const [day, month] = trimmed.split('-');
    const year = new Date().getFullYear().toString();
    return `${year}-${month}-${day}`;
  }

  private normalizeToDayMonth(value: string): string | null {
    const trimmed = value.trim();
    if (!trimmed) {
      return '';
    }
    if (/^\d{2}-\d{2}$/.test(trimmed)) {
      return trimmed;
    }
    if (/^\d{4}-\d{2}-\d{2}$/.test(trimmed)) {
      const parts = trimmed.split('-');
      return `${parts[2]}-${parts[1]}`;
    }
    return null;
  }

  private schedulePaymentDateSave(value: string): void {
    if (this.paymentDateSaveTimer) {
      window.clearTimeout(this.paymentDateSaveTimer);
    }
    this.paymentDateSaveTimer = window.setTimeout(() => {
      this.savePaymentDate(value);
    }, 400);
  }

  clearPaymentDate(): void {
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit payment dates', 'paymentDateEdit');
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
      this.permissionService.notifyRoleDenied('edit payment dates', 'paymentDateEdit');
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
    if (cleaned && !/^\d{2}-\d{2}$/.test(cleaned)) {
      this.customerStatus = 'Invalid date format. Use dd-MM.';
      this.customerStatusIsError = true;
      this.notificationService.showError('Invalid date format. Use DD-MM.', 4000);
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
        this.customerStatus = 'Unable to save payment date.';
        this.customerStatusIsError = true;
        this.notificationService.showError('Failed to save payment date.', 3000);
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
    this.editingLocation = true;
  }

  cancelLocationEdit(): void {
    this.editingLocation = false;
    // Reset to current values from summary
    if (this.customerSummary) {
      this.locationAddress = this.customerSummary.address || '';
      this.locationLatitude = this.customerSummary.latitude ?? null;
      this.locationLongitude = this.customerSummary.longitude ?? null;
    }
  }

  saveLocation(locationData: LocationData): void {
    if (!this.canEditCustomerLocation) {
      this.permissionService.notifyRoleDenied('edit customer location', 'customerLocationEdit');
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
      return;
    }
    this.customerStatus = '';
    this.customerStatusIsError = false;
    this.api.updateCustomerLocation(customer, {
      address: locationData.address || null,
      latitude: locationData.latitude === 0 ? null : locationData.latitude,
      longitude: locationData.longitude === 0 ? null : locationData.longitude
    }).subscribe({
      next: () => {
        this.locationAddress = locationData.address;
        this.locationLatitude = locationData.latitude;
        this.locationLongitude = locationData.longitude;
        this.editingLocation = false;
        // Refresh customer summary to get latest data
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

  deleteLocation(): void {
    if (!this.canEditCustomerLocation) {
      this.permissionService.notifyRoleDenied('edit customer location', 'customerLocationEdit');
      return;
    }
    const customer = this.getCustomerNameForMasterWrites();
    if (!customer) {
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
        // Cleanup map if exists
        if (this.locationMap) {
          this.locationMap.remove();
          this.locationMap = null;
          this.locationMarker = null;
          this.mapInitialized = false;
        }
        // Refresh customer summary to get latest data
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

  ngAfterViewChecked(): void {
    if (this.locationMapPreview && !this.mapInitialized && this.mapExpanded && this.customerSummary?.latitude && this.customerSummary?.longitude) {
      setTimeout(() => {
        this.initLocationMap();
      }, 100);
    }
  }

  initLocationMap(): void {
    if (!this.locationMapPreview || this.mapInitialized || !this.customerSummary?.latitude || !this.customerSummary?.longitude) {
      return;
    }

    const lat = this.customerSummary.latitude;
    const lng = this.customerSummary.longitude;

    this.locationMap = L.map(this.locationMapPreview.nativeElement).setView([lat, lng], 15);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap contributors',
      maxZoom: 19
    }).addTo(this.locationMap);

    this.locationMarker = L.marker([lat, lng]).addTo(this.locationMap);
    this.mapInitialized = true;
    
    // Invalidate map size when expanded/collapsed
    setTimeout(() => {
      if (this.locationMap) {
        this.locationMap.invalidateSize();
      }
    }, 300);
  }

  toggleAddress(): void {
    this.addressExpanded = !this.addressExpanded;
  }

  toggleMap(): void {
    this.mapExpanded = !this.mapExpanded;
    if (this.mapExpanded) {
      // Initialize map if not already initialized
      if (!this.mapInitialized && this.locationMapPreview && this.customerSummary?.latitude && this.customerSummary?.longitude) {
        setTimeout(() => {
          this.initLocationMap();
        }, 100);
      } else if (this.locationMap) {
        // Resize map when expanded
        setTimeout(() => {
          if (this.locationMap) {
            this.locationMap.invalidateSize();
          }
        }, 300);
      }
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
        // Set location data
        this.locationAddress = summary.address || '';
        this.locationLatitude = summary.latitude ?? null;
        this.locationLongitude = summary.longitude ?? null;
        // Reset map to reinitialize with new location
        if (this.locationMap) {
          this.locationMap.remove();
          this.locationMap = null;
          this.locationMarker = null;
          this.mapInitialized = false;
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

    // Prepare customer details at the top
    const customerDetails = [
      ['Customer Name', this.selectedCustomerName],
      ['Phone Number', this.customerSummary.phoneNumber || 'Not available'],
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
    const allData = [watermarkRow, ...customerDetails, headers, summaryRow, ...tableData];
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
    doc.text(`Phone Number: ${this.customerSummary.phoneNumber || 'Not available'}`, 14, yPos);
    yPos += 10;

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
    
    // Clean phone number (remove spaces, dashes, etc.)
    const cleanPhone = phoneNumber.replace(/[\s\-\(\)]/g, '');
    
    // Copy to clipboard first
    this.copyPhoneNumber(phoneNumber, false);
    
    // Initiate call using tel: protocol
    window.location.href = `tel:${cleanPhone}`;
  }

  copyPhoneNumber(phoneNumber: string | null | undefined, showNotification: boolean = true): void {
    if (!phoneNumber || phoneNumber.trim() === '') {
      if (showNotification) {
        this.notificationService.showError('No phone number available to copy.');
      }
      return;
    }
    
    // Clean phone number for copying
    const cleanPhone = phoneNumber.replace(/[\s\-\(\)]/g, '');
    
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
    // Cleanup map
    if (this.locationMap) {
      this.locationMap.remove();
      this.locationMap = null;
      this.locationMarker = null;
      this.mapInitialized = false;
    }
    // Complete destroy subject to cleanup subscriptions
    this.destroy$.next();
    this.destroy$.complete();
  }
}


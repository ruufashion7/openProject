import { Component, OnInit, OnDestroy, ChangeDetectorRef, ElementRef, HostListener, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, takeUntil } from 'rxjs';
import { ApiService, PaymentDateCustomerCard, ExcludedCustomerView, RetainedCustomerView, DrivePaymentDateSyncStatus } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { PageStateComponent } from '../shared/page-state/page-state.component';
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
import {
  formatPhoneDisplay,
  formatPhoneForTel,
  formatPhoneForWhatsApp,
  phoneDigitsMatch
} from '../shared/phone.util';
import {
  getPaymentDateBorderClass as paymentDateBorderClass,
  getPaymentDateTone as paymentDateTone,
  isPaymentDatePast,
  isValidPaymentDateFormat,
  matchesPaymentDateFilter,
  normalizeOverduePaymentDate,
  normalizeToDayMonth,
  PAYMENT_DATE_SAVE_DEBOUNCE_MS,
  PaymentDateFilterMode,
  todayIsoDate,
  toIsoDate
} from '../shared/payment-date.util';

interface FilterState {
  paymentDate: PaymentDateFilterMode;
  whatsappStatus: 'all' | 'not sent' | 'sent' | 'delivered';
  customerCategory: 'all' | 'semi-wholesale' | 'A' | 'B' | 'C';
  followUp: 'all' | 'needed' | 'not-needed';
  location: 'all' | 'with' | 'without';
  orderDate: 'all' | '0-45' | '46-85' | '85+';
  retained: 'all' | 'yes' | 'no';
  places: string[];
  creditLimit: 'all' | 'over' | 'within' | 'none';
}

/** Per-option counts with other filters applied (cascading). */
interface FilterDimensionCounts {
  paymentDate: Record<PaymentDateFilterMode, number>;
  whatsappStatus: Record<'all' | 'not sent' | 'sent' | 'delivered', number>;
  customerCategory: Record<'all' | 'semi-wholesale' | 'A' | 'B' | 'C', number>;
  followUp: Record<'all' | 'needed' | 'not-needed', number>;
  location: Record<'all' | 'with' | 'without', number>;
  orderDate: Record<'all' | '0-45' | '46-85' | '85+', number>;
  retained: Record<'all' | 'yes' | 'no', number>;
  creditLimit: Record<'all' | 'over' | 'within' | 'none', number>;
}

@Component({
  selector: 'app-outstanding-due',
  standalone: true,
  imports: [CommonModule, FormsModule, PageStateComponent],
  templateUrl: './outstanding-due.component.html',
  styleUrl: './outstanding-due.component.css'
})
export class OutstandingDueComponent implements OnInit, OnDestroy {
  formatPhoneDisplay = formatPhoneDisplay;

  // Status
  status: 'idle' | 'loading' | 'failed' = 'loading';
  message = '';
  
  // Data
  cards: PaymentDateCustomerCard[] = [];
  filteredCards: PaymentDateCustomerCard[] = [];
  totalAmount = 0;
  totalCustomers = 0;
  
  // Search
  searchQuery = '';
  searchSuggestions: Array<{name: string, phone: string}> = [];
  showSuggestions = false;
  
  // Filters
  filters: FilterState = {
    paymentDate: 'all',
    whatsappStatus: 'all',
    customerCategory: 'all',
    followUp: 'all',
    location: 'all',
    orderDate: 'all',
    retained: 'all',
    places: [],
    creditLimit: 'all'
  };

  /** Autocomplete input value for place */
  placeSearchQuery = '';
  placeSuggestionsOpen = false;
  @ViewChild('placeAutocompleteWrap') placeAutocompleteWrap?: ElementRef<HTMLElement>;

  /** Number of place suggestions to show initially; more load on scroll */
  readonly PLACE_PAGE_SIZE = 25;
  placeSuggestionsVisibleCount = this.PLACE_PAGE_SIZE;

  /** Unique places parsed from customer names (text in brackets), sorted. e.g. "Name (Nallasopara)" -> Nallasopara */
  get placeOptions(): string[] {
    const set = new Set<string>();
    for (const card of this.cards) {
      const place = this.getPlace(card);
      if (place) set.add(place);
    }
    return Array.from(set).sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
  }

  /** Full list of place options (alphabetically sorted), filtered by search text. */
  get placeSuggestions(): string[] {
    const q = this.placeSearchQuery.trim().toLowerCase();
    if (!q) {
      return this.placeOptions;
    }
    return this.placeOptions.filter(p => p.toLowerCase().includes(q));
  }

  /** Visible slice of place suggestions for the dropdown (grows on scroll). */
  get placeSuggestionsSlice(): string[] {
    return this.placeSuggestions.slice(0, this.placeSuggestionsVisibleCount);
  }

  // Edits
  dateEdits: Record<string, string> = {};
  whatsappStatuses: Record<string, string> = {};
  customerCategories: Record<string, string> = {};
  followUpFlags: Record<string, boolean> = {};
  
  // UI State
  viewMode: 'grid' | 'list' = 'grid';
  sortBy: 'amount' | 'name' | 'date' = 'amount';
  sortOrder: 'asc' | 'desc' = 'desc';
  selectedCard: PaymentDateCustomerCard | null = null;
  showFilters = false;
  showCategoryLimitsPanel = false;
  isAdmin = false;
  categoryLimitEdits: Record<string, string> = {
    'semi-wholesale': '',
    A: '',
    B: '',
    C: ''
  };
  savingCategoryLimits = false;
  
  // Permissions
  canEditPaymentDate = false;
  canChangeWhatsappDate = false;
  canChangeFollowUp = false;
  canEditCustomerCategory = false;
  canExcludeCustomer = false;
  canRetainCustomer = false;
  
  // Ignored customers
  showIgnoredPanel = false;
  excludedCustomers: ExcludedCustomerView[] = [];
  ignoreCustomerInput = '';
  ignoreNameSuggestions: string[] = [];
  showIgnoreNameSuggestions = false;
  readonly nameSuggestLimit = 500;
  private ignoreNameSuggestQuery = '';

  // Drive Excel next-payment-date sync
  showDriveSyncPanel = false;
  driveSync: DrivePaymentDateSyncStatus | null = null;
  driveSyncing = false;

  // Retained customers
  showRetainedPanel = false;
  retainedCustomers: RetainedCustomerView[] = [];
  retainCustomerInput = '';
  retainNameSuggestions: string[] = [];
  showRetainNameSuggestions = false;
  private retainNameSuggestQuery = '';

  // Timers
  private saveTimers: Record<string, number> = {};
  private processingDateChange: Record<string, boolean> = {};
  private searchTimer: any = null;
  private ignoreSuggestTimer: any = null;
  private retainSuggestTimer: any = null;
  private retainToggleBusy: Record<string, boolean> = {};
  private readonly filterStorageKey = 'outstandingDueV2.filters';

  /** Cascading counts for filter pills (recomputed in {@link updateFilteredCards}). */
  filterCounts: FilterDimensionCounts = {
    paymentDate: { all: 0, past: 0, today: 0, tomorrow: 0, future: 0, none: 0 },
    whatsappStatus: { all: 0, 'not sent': 0, sent: 0, delivered: 0 },
    customerCategory: { all: 0, 'semi-wholesale': 0, A: 0, B: 0, C: 0 },
    followUp: { all: 0, needed: 0, 'not-needed': 0 },
    location: { all: 0, with: 0, without: 0 },
    orderDate: { all: 0, '0-45': 0, '46-85': 0, '85+': 0 },
    retained: { all: 0, yes: 0, no: 0 },
    creditLimit: { all: 0, over: 0, within: 0, none: 0 }
  };

  /** Rows matching current filters except place (scope for place picker). */
  countPlaceScope = 0;

  /** Per-place counts with place filter cleared (for dropdown). */
  placeOptionCounts: Record<string, number> = {};
  
  // Subscription management
  private destroy$ = new Subject<void>();

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
        if (!this.permissionService.canAccessOutstandingPage()) {
          this.notificationService.showPermissionError();
          this.router.navigateByUrl('/welcome');
          return;
        }

        this.canEditPaymentDate = this.permissionService.canEditPaymentDate();
        this.canChangeWhatsappDate = this.permissionService.canChangeWhatsappDate();
        this.canChangeFollowUp = this.permissionService.canChangeFollowUp();
        this.canEditCustomerCategory = this.permissionService.canEditCustomerCategory();
        this.canExcludeCustomer = this.permissionService.canExcludeCustomer();
        this.canRetainCustomer = this.permissionService.canRetainCustomer();
        this.isAdmin = this.auth.isAdmin();

        this.restoreFilters();
        this.loadData();
        this.loadExcludedCustomers();
        this.loadRetainedCustomers();
        this.loadDriveSyncStatus();
        if (this.isAdmin) {
          this.loadCategoryCreditLimits();
        }
      });
  }

  ngOnDestroy(): void {
    // Clear timers
    Object.values(this.saveTimers).forEach(timer => clearTimeout(timer));
    if (this.searchTimer) clearTimeout(this.searchTimer);
    if (this.ignoreSuggestTimer) clearTimeout(this.ignoreSuggestTimer);
    if (this.retainSuggestTimer) clearTimeout(this.retainSuggestTimer);
    // Complete destroy subject to cleanup subscriptions
    this.destroy$.next();
    this.destroy$.complete();
  }

  refreshPage(): void {
    window.location.reload();
  }

  loadData(): void {
    this.status = 'loading';
    this.api.getUploadStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (uploadStatus) => {
          const ready = uploadStatus.ready ?? (uploadStatus.hasDetailed && uploadStatus.hasReceivable);
          if (!ready) {
            this.status = 'idle';
            this.message = 'Latest uploads not available.';
            this.cards = [];
            this.updateFilteredCards();
            return;
          }
          this.loadOutstandingDue();
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.status = 'failed';
            this.message = 'Session expired. Please login again.';
            this.logout();
            return;
          }
          this.status = 'failed';
          this.message = 'Unable to load upload status.';
        }
      });
  }

  private loadOutstandingDue(): void {
    this.api.getOutstandingDue()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (cards) => {
          // Filter out "Total" card - it's not a customer
          this.cards = cards.filter(card => {
            const customerName = (card.customer || '').toLowerCase().trim();
            return customerName !== 'total';
          });
          this.initializeEdits();
          this.updateFilteredCards();
          this.status = 'idle';
          this.message = this.cards.length ? '' : 'No payment data available.';
          this.loadExcludedCustomers();
          this.loadRetainedCustomers();
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.status = 'failed';
            this.message = 'Session expired. Please login again.';
            this.logout();
            return;
          }
          this.status = 'failed';
          if (err.status === 403) {
            this.message = 'You do not have permission to view Outstanding Due.';
          } else if (err.status === 404) {
            this.message = 'Outstanding Due API not found. Restart the backend after pulling latest changes.';
          } else if (err.status === 0) {
            this.message = 'Cannot reach the server. Check that the backend is running on port 8080.';
          } else {
            this.message = 'Unable to load outstanding due data.';
          }
        }
      });
  }

  private initializeEdits(): void {
    this.dateEdits = {};
    this.whatsappStatuses = {};
    this.followUpFlags = {};
    this.customerCategories = {};
    for (const card of this.cards) {
      if (card.customer) {
        const effectiveDate = normalizeOverduePaymentDate(card.nextPaymentDate);
        card.nextPaymentDate = effectiveDate || null;
        this.dateEdits[card.customer] = effectiveDate;
        this.whatsappStatuses[card.customer] = card.whatsAppStatus ?? 'not sent';
        // Default to 'A' if no category is set, and save it automatically
        const defaultCategory = card.customerCategory ?? 'A';
        this.customerCategories[card.customer] = defaultCategory;
        // If customer doesn't have a category set, save 'A' as default (only when allowed to edit master data)
        if (!card.customerCategory && this.canEditCustomerCategory) {
          this.api.updateCustomerCategory(card.customer, 'A')
            .pipe(takeUntil(this.destroy$))
            .subscribe({
              next: () => {
                const foundCard = this.cards.find(c => c.customer === card.customer);
                if (foundCard) {
                  foundCard.customerCategory = 'A';
                }
              },
              error: () => {
                // Silently fail - category will still show as 'A' in UI
              }
            });
        }
        this.followUpFlags[card.customer] = card.needsFollowUp ?? false;
      }
    }
  }

  updateFilteredCards(): void {
    let filtered = this.filterCardsByState(this.cards, this.filters);

    filtered.sort((a, b) => {
      let comparison = 0;
      if (this.sortBy === 'amount') {
        comparison = this.getDisplayAmount(a) - this.getDisplayAmount(b);
      } else if (this.sortBy === 'name') {
        comparison = (a.customer || '').localeCompare(b.customer || '');
      } else if (this.sortBy === 'date') {
        const dateA = a.nextPaymentDate || '';
        const dateB = b.nextPaymentDate || '';
        comparison = dateA.localeCompare(dateB);
      }
      return this.sortOrder === 'asc' ? comparison : -comparison;
    });

    this.filteredCards = filtered;
    this.updateTotals();
    this.recomputeFilterOptionCounts();
  }

  /** Readable count for filter pills (grouped digits). */
  formatFilterCount(n: number): string {
    return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
  }

  private mergeFilterState(overrides: Partial<FilterState>): FilterState {
    return {
      ...this.filters,
      ...overrides,
      places: overrides.places !== undefined ? [...overrides.places] : [...this.filters.places]
    };
  }

  /**
   * Applies search + all filter dimensions from {@code f} (same rules as the main list).
   */
  private filterCardsByState(cards: PaymentDateCustomerCard[], f: FilterState): PaymentDateCustomerCard[] {
    let filtered = [...cards];

    if (this.searchQuery.trim()) {
      const query = this.searchQuery.toLowerCase().trim();
      const normalizedQuery = query.replace(/\D/g, '');
      filtered = filtered.filter(card => {
        const name = (card.customer || '').toLowerCase();
        const place = (card.place || this.getPlace(card) || '').toLowerCase();
        const nameMatch = name.includes(query);
        const placeMatch = !!place && place.includes(query);
        const phoneMatch = normalizedQuery ? phoneDigitsMatch(card.phoneNumber, normalizedQuery) : false;
        return nameMatch || placeMatch || phoneMatch;
      });
    }

    if (f.paymentDate !== 'all') {
      filtered = filtered.filter(card => this.cardMatchesPaymentDate(card, f.paymentDate));
    }

    if (f.whatsappStatus !== 'all') {
      filtered = filtered.filter(card => this.cardMatchesWhatsappStatus(card, f.whatsappStatus));
    }

    if (f.customerCategory !== 'all') {
      filtered = filtered.filter(card => this.getCustomerCategory(card) === f.customerCategory);
    }

    if (f.followUp !== 'all') {
      filtered = filtered.filter(card => {
        const needsFollowUp = card.needsFollowUp ?? false;
        return f.followUp === 'needed' ? needsFollowUp : !needsFollowUp;
      });
    }

    if (f.location !== 'all') {
      filtered = filtered.filter(card => {
        const hasLocation = this.hasLocation(card);
        return f.location === 'with' ? hasLocation : !hasLocation;
      });
    }

    if (f.places.length > 0) {
      filtered = filtered.filter(card => {
        const place = this.getPlace(card);
        return place !== '' && f.places.includes(place);
      });
    }

    if (f.orderDate !== 'all') {
      filtered = filtered.filter(card => this.cardMatchesOrderDateBucket(card, f.orderDate));
    }

    if (f.retained !== 'all') {
      filtered = filtered.filter(card => {
        const isRetained = !!card.retained;
        return f.retained === 'yes' ? isRetained : !isRetained;
      });
    }

    if (f.creditLimit !== 'all') {
      filtered = filtered.filter(card => this.cardMatchesCreditLimit(card, f.creditLimit));
    }

    return filtered;
  }

  private cardMatchesCreditLimit(card: PaymentDateCustomerCard, mode: FilterState['creditLimit']): boolean {
    if (mode === 'all') {
      return true;
    }
    const hasLimit = card.effectiveCreditLimit != null;
    if (mode === 'none') {
      return !hasLimit;
    }
    if (mode === 'over') {
      return !!card.overCreditLimit;
    }
    return hasLimit && !card.overCreditLimit;
  }

  getOverLimitCount(): number {
    return this.filteredCards.filter(c => c.overCreditLimit).length;
  }

  getCreditLimitSourceLabel(card: PaymentDateCustomerCard): string {
    if (card.creditLimitSource === 'override') {
      return 'Custom';
    }
    if (card.creditLimitSource === 'category') {
      const cat = card.customerCategory ?? this.getCustomerCategory(card);
      return cat ? `Cat ${cat}` : 'Category';
    }
    return '';
  }

  private cardMatchesPaymentDate(card: PaymentDateCustomerCard, mode: FilterState['paymentDate']): boolean {
    const date = this.dateEdits[card.customer || ''] ?? card.nextPaymentDate;
    return matchesPaymentDateFilter(date, mode);
  }

  private cardMatchesWhatsappStatus(card: PaymentDateCustomerCard, mode: FilterState['whatsappStatus']): boolean {
    if (mode === 'all') {
      return true;
    }
    return (card.whatsAppStatus || 'not sent') === mode;
  }

  private cardMatchesOrderDateBucket(card: PaymentDateCustomerCard, mode: FilterState['orderDate']): boolean {
    if (mode === 'all') {
      return true;
    }
    const within = card.withinAmount ?? 0;
    const mid = card.midAmount ?? 0;
    const beyond = card.beyondAmount ?? 0;
    if (mode === '0-45') {
      return within > 0;
    }
    if (mode === '46-85') {
      return mid > 0;
    }
    if (mode === '85+') {
      return beyond > 0;
    }
    return true;
  }

  /** Amount shown on card / totals — bucket amount when an ageing filter is active. */
  getDisplayAmount(card: PaymentDateCustomerCard): number {
    const mode = this.filters.orderDate;
    if (mode === '0-45') {
      return card.withinAmount ?? 0;
    }
    if (mode === '46-85') {
      return card.midAmount ?? 0;
    }
    if (mode === '85+') {
      return card.beyondAmount ?? 0;
    }
    return card.totalAmount;
  }

  getAmountDueLabel(): string {
    switch (this.filters.orderDate) {
      case '0-45':
        return '1-45 Days Due';
      case '46-85':
        return '46-85 Days Due';
      case '85+':
        return '85+ Days Due';
      default:
        return 'Amount Due';
    }
  }

  getTotalAmountLabel(): string {
    switch (this.filters.orderDate) {
      case '0-45':
        return 'Total (1-45 Days)';
      case '46-85':
        return 'Total (46-85 Days)';
      case '85+':
        return 'Total (85+ Days)';
      default:
        return 'Total Amount';
    }
  }

  private recomputeFilterOptionCounts(): void {
    const basePayment = this.filterCardsByState(this.cards, this.mergeFilterState({ paymentDate: 'all' }));
    this.filterCounts.paymentDate = {
      all: basePayment.length,
      past: basePayment.filter(c => this.cardMatchesPaymentDate(c, 'past')).length,
      today: basePayment.filter(c => this.cardMatchesPaymentDate(c, 'today')).length,
      tomorrow: basePayment.filter(c => this.cardMatchesPaymentDate(c, 'tomorrow')).length,
      future: basePayment.filter(c => this.cardMatchesPaymentDate(c, 'future')).length,
      none: basePayment.filter(c => this.cardMatchesPaymentDate(c, 'none')).length
    };

    const baseWa = this.filterCardsByState(this.cards, this.mergeFilterState({ whatsappStatus: 'all' }));
    this.filterCounts.whatsappStatus = {
      all: baseWa.length,
      'not sent': baseWa.filter(c => this.cardMatchesWhatsappStatus(c, 'not sent')).length,
      sent: baseWa.filter(c => this.cardMatchesWhatsappStatus(c, 'sent')).length,
      delivered: baseWa.filter(c => this.cardMatchesWhatsappStatus(c, 'delivered')).length
    };

    const baseCat = this.filterCardsByState(this.cards, this.mergeFilterState({ customerCategory: 'all' }));
    this.filterCounts.customerCategory = {
      all: baseCat.length,
      'semi-wholesale': baseCat.filter(c => this.getCustomerCategory(c) === 'semi-wholesale').length,
      A: baseCat.filter(c => this.getCustomerCategory(c) === 'A').length,
      B: baseCat.filter(c => this.getCustomerCategory(c) === 'B').length,
      C: baseCat.filter(c => this.getCustomerCategory(c) === 'C').length
    };

    const baseFu = this.filterCardsByState(this.cards, this.mergeFilterState({ followUp: 'all' }));
    this.filterCounts.followUp = {
      all: baseFu.length,
      needed: baseFu.filter(c => {
        const needsFollowUp = c.needsFollowUp ?? false;
        return needsFollowUp;
      }).length,
      'not-needed': baseFu.filter(c => {
        const needsFollowUp = c.needsFollowUp ?? false;
        return !needsFollowUp;
      }).length
    };

    const baseLoc = this.filterCardsByState(this.cards, this.mergeFilterState({ location: 'all' }));
    this.filterCounts.location = {
      all: baseLoc.length,
      with: baseLoc.filter(c => this.hasLocation(c)).length,
      without: baseLoc.filter(c => !this.hasLocation(c)).length
    };

    const baseOrder = this.filterCardsByState(this.cards, this.mergeFilterState({ orderDate: 'all' }));
    this.filterCounts.orderDate = {
      all: baseOrder.length,
      '0-45': baseOrder.filter(c => this.cardMatchesOrderDateBucket(c, '0-45')).length,
      '46-85': baseOrder.filter(c => this.cardMatchesOrderDateBucket(c, '46-85')).length,
      '85+': baseOrder.filter(c => this.cardMatchesOrderDateBucket(c, '85+')).length
    };

    const baseRetained = this.filterCardsByState(this.cards, this.mergeFilterState({ retained: 'all' }));
    this.filterCounts.retained = {
      all: baseRetained.length,
      yes: baseRetained.filter(c => !!c.retained).length,
      no: baseRetained.filter(c => !c.retained).length
    };

    const baseCredit = this.filterCardsByState(this.cards, this.mergeFilterState({ creditLimit: 'all' }));
    this.filterCounts.creditLimit = {
      all: baseCredit.length,
      over: baseCredit.filter(c => this.cardMatchesCreditLimit(c, 'over')).length,
      within: baseCredit.filter(c => this.cardMatchesCreditLimit(c, 'within')).length,
      none: baseCredit.filter(c => this.cardMatchesCreditLimit(c, 'none')).length
    };

    const basePlace = this.filterCardsByState(this.cards, this.mergeFilterState({ places: [] }));
    this.countPlaceScope = basePlace.length;
    const next: Record<string, number> = {};
    for (const p of this.placeOptions) {
      next[p] = basePlace.filter(c => this.getPlace(c) === p).length;
    }
    this.placeOptionCounts = next;
  }

  updateTotals(): void {
    // Exclude "Total" from customer count
    const validCustomers = this.filteredCards.filter(card => {
      const customerName = (card.customer || '').toLowerCase().trim();
      return customerName !== 'total';
    });
    
    this.totalAmount = this.filteredCards.reduce((sum, card) => sum + this.getDisplayAmount(card), 0);
    this.totalCustomers = validCustomers.length;
  }

  onSearchChange(event: any): void {
    const query = event.target?.value || '';
    this.searchQuery = query;
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.updateFilteredCards();
      this.updateSearchSuggestions();
    }, 300);
  }

  updateSearchSuggestions(): void {
    if (this.searchQuery.trim().length < 1) {
      this.searchSuggestions = [];
      this.showSuggestions = false;
      return;
    }
    const query = this.searchQuery.toLowerCase().trim();
    const normalizedQuery = query.replace(/\D/g, '');
    
    this.searchSuggestions = this.cards
      .filter(card => {
        const name = (card.customer || '').toLowerCase();
        const place = (card.place || this.getPlace(card) || '').toLowerCase();
        const nameMatch = name.includes(query);
        const placeMatch = !!place && place.includes(query);
        const phoneMatch = normalizedQuery ? phoneDigitsMatch(card.phoneNumber, normalizedQuery) : false;
        return nameMatch || placeMatch || phoneMatch;
      })
      .sort((a, b) => {
        const aName = (a.customer || '').toLowerCase();
        const bName = (b.customer || '').toLowerCase();
        const aPos = aName.indexOf(query);
        const bPos = bName.indexOf(query);
        if (aPos !== bPos) {
          return (aPos < 0 ? 999 : aPos) - (bPos < 0 ? 999 : bPos);
        }
        return aName.localeCompare(bName);
      })
      .slice(0, 30)
      .map(card => ({
        name: card.customer || '',
        phone: formatPhoneDisplay(card.phoneNumber)
      }));
    this.showSuggestions = this.searchSuggestions.length > 0;
  }

  selectSuggestion(suggestion: {name: string, phone: string}): void {
    // Use customer name for search, but it will match by name or phone
    this.searchQuery = suggestion.name || suggestion.phone;
    this.showSuggestions = false;
    this.updateFilteredCards();
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.searchSuggestions = [];
    this.showSuggestions = false;
    this.updateFilteredCards();
  }

  setFilter(type: keyof FilterState, value: any): void {
    (this.filters as any)[type] = value;
    this.saveFilters();
    this.updateFilteredCards();
  }

  clearFilters(): void {
    this.filters = {
      paymentDate: 'all',
      whatsappStatus: 'all',
      customerCategory: 'all',
      followUp: 'all',
      location: 'all',
      orderDate: 'all',
      retained: 'all',
      places: [],
      creditLimit: 'all'
    };
    this.saveFilters();
    this.updateFilteredCards();
  }

  hasActiveFilters(): boolean {
    return this.filters.paymentDate !== 'all' ||
           this.filters.whatsappStatus !== 'all' ||
           this.filters.customerCategory !== 'all' ||
           this.filters.followUp !== 'all' ||
           this.filters.location !== 'all' ||
           this.filters.orderDate !== 'all' ||
           this.filters.retained !== 'all' ||
           this.filters.creditLimit !== 'all' ||
           this.filters.places.length > 0;
  }

  isPlaceSelected(place: string): boolean {
    return this.filters.places.includes(place);
  }

  togglePlaceFromDropdown(place: string): void {
    if (!place) {
      return;
    }
    if (this.filters.places.includes(place)) {
      this.filters.places = this.filters.places.filter(p => p !== place);
    } else {
      this.filters.places = [...this.filters.places, place].sort((a, b) =>
        a.localeCompare(b, undefined, { sensitivity: 'base' })
      );
    }
    this.saveFilters();
    this.updateFilteredCards();
  }

  togglePlaceDropdown(): void {
    this.placeSuggestionsOpen = !this.placeSuggestionsOpen;
    if (this.placeSuggestionsOpen) {
      this.placeSuggestionsVisibleCount = this.PLACE_PAGE_SIZE;
    }
  }

  onPlaceInputFocus(): void {
    this.placeSuggestionsVisibleCount = this.PLACE_PAGE_SIZE;
    this.placeSuggestionsOpen = true;
  }

  onPlaceSearchChange(): void {
    this.placeSuggestionsVisibleCount = this.PLACE_PAGE_SIZE;
    if (!this.placeSuggestionsOpen) {
      this.placeSuggestionsOpen = true;
    }
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.placeSuggestionsOpen) {
      return;
    }
    const root = this.placeAutocompleteWrap?.nativeElement;
    if (root && !root.contains(event.target as Node)) {
      this.placeSuggestionsOpen = false;
    }
  }

  onPlaceSuggestionsScroll(e: Event): void {
    const el = e.target as HTMLElement;
    if (!el || el.scrollHeight <= 0) return;
    const threshold = 40;
    const nearBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - threshold;
    if (nearBottom && this.placeSuggestionsVisibleCount < this.placeSuggestions.length) {
      this.placeSuggestionsVisibleCount = Math.min(
        this.placeSuggestionsVisibleCount + this.PLACE_PAGE_SIZE,
        this.placeSuggestions.length
      );
      this.cdr.markForCheck();
    }
  }

  onPlaceKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      this.placeSuggestionsOpen = false;
      return;
    }
    if (event.key === 'Enter' && this.placeSuggestionsSlice.length > 0) {
      event.preventDefault();
      this.togglePlaceFromDropdown(this.placeSuggestionsSlice[0]);
    }
  }

  removePlace(place: string): void {
    this.filters.places = this.filters.places.filter(p => p !== place);
    this.saveFilters();
    this.updateFilteredCards();
  }

  clearPlaceFilter(): void {
    this.filters.places = [];
    this.saveFilters();
    this.updateFilteredCards();
  }

  getPaymentDateBorderClass(card: PaymentDateCustomerCard): string {
    const date = this.dateEdits[card.customer || ''] || card.nextPaymentDate || '';
    return paymentDateBorderClass(paymentDateTone(date));
  }

  onDateKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      (event.target as HTMLInputElement)?.blur();
    }
  }

  onPaymentDateInput(card: PaymentDateCustomerCard, event: Event, input: HTMLInputElement): void {
    if (!card.customer || this.processingDateChange[card.customer]) {
      return;
    }
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    if (input.type === 'date') {
      return;
    }

    const value = (event.target as HTMLInputElement).value;
    this.dateEdits[card.customer] = value;

    const normalized = normalizeToDayMonth(value);
    if (normalized) {
      if (isPaymentDatePast(normalized)) {
        this.dateEdits[card.customer] = card.nextPaymentDate ?? '';
        this.notificationService.showError('Payment date cannot be before today.', 4000);
        return;
      }
      this.dateEdits[card.customer] = normalized;
      const foundCard = this.cards.find(c => c.customer === card.customer);
      if (foundCard) {
        foundCard.nextPaymentDate = normalized || null;
      }
      this.schedulePaymentDateSave(card.customer, normalized);
      this.updateFilteredCards();
    }
  }

  openDatePicker(card: PaymentDateCustomerCard, event: FocusEvent, input: HTMLInputElement): void {
    if (!this.canEditPaymentDate) {
      (event.target as HTMLInputElement)?.blur();
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    if (!card.customer || !input || input.type === 'date') {
      return;
    }

    const current = this.dateEdits[card.customer] ?? '';
    const iso = toIsoDate(current);
    input.type = 'date';
    input.min = todayIsoDate();
    if (iso) {
      input.value = iso;
    }
  }

  onDateChange(card: PaymentDateCustomerCard, event: Event, input: HTMLInputElement): void {
    event.preventDefault();
    event.stopPropagation();

    if (!card.customer || this.processingDateChange[card.customer]) {
      return;
    }
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    if (input.type !== 'date') {
      return;
    }

    const value = input.value;
    if (!value) {
      input.type = 'text';
      return;
    }

    const normalized = normalizeToDayMonth(value);
    if (!normalized) {
      input.type = 'text';
      return;
    }
    if (isPaymentDatePast(normalized)) {
      input.type = 'text';
      input.value = card.nextPaymentDate ?? '';
      this.dateEdits[card.customer] = card.nextPaymentDate ?? '';
      this.notificationService.showError('Payment date cannot be before today.', 4000);
      return;
    }

    this.processingDateChange[card.customer] = true;
    input.type = 'text';
    input.value = normalized;
    this.dateEdits[card.customer] = normalized;

    const foundCard = this.cards.find(c => c.customer === card.customer);
    if (foundCard) {
      foundCard.nextPaymentDate = normalized;
    }

    this.savePaymentDate(card.customer, normalized);
    this.updateFilteredCards();
    this.cdr.detectChanges();

    window.setTimeout(() => {
      delete this.processingDateChange[card.customer!];
    }, 100);
  }

  onDateInputBlur(event: Event, input: HTMLInputElement): void {
    if (this.isProcessingAnyDateChange()) {
      return;
    }
    if (input.type === 'date') {
      input.type = 'text';
    }
  }

  clearPaymentDate(card: PaymentDateCustomerCard): void {
    if (!card.customer) {
      return;
    }
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    this.dateEdits[card.customer] = '';
    const foundCard = this.cards.find(c => c.customer === card.customer);
    if (foundCard) {
      foundCard.nextPaymentDate = null;
    }
    this.savePaymentDate(card.customer, '');
    this.updateFilteredCards();
  }

  private isProcessingAnyDateChange(): boolean {
    return Object.values(this.processingDateChange).some(Boolean);
  }

  private schedulePaymentDateSave(customer: string, value: string): void {
    if (this.saveTimers[customer]) {
      clearTimeout(this.saveTimers[customer]);
    }
    this.saveTimers[customer] = window.setTimeout(() => {
      this.savePaymentDate(customer, value);
    }, PAYMENT_DATE_SAVE_DEBOUNCE_MS);
  }

  getWhatsAppStatus(card: PaymentDateCustomerCard): 'not sent' | 'sent' | 'delivered' {
    const status = this.whatsappStatuses[card.customer || ''] || card.whatsAppStatus || 'not sent';
    return status as 'not sent' | 'sent' | 'delivered';
  }

  getWhatsAppStatusDisplay(card: PaymentDateCustomerCard): string {
    const status = this.getWhatsAppStatus(card);
    switch (status) {
      case 'not sent':
        return 'Not Sent';
      case 'sent':
        return 'Sent';
      case 'delivered':
        return 'Delivered';
      default:
        return status;
    }
  }

  getWhatsAppStatusBorderClass(card: PaymentDateCustomerCard): string {
    const status = this.getWhatsAppStatus(card);
    switch (status) {
      case 'not sent':
        return 'border-grey';
      case 'sent':
        return 'border-yellow';
      case 'delivered':
        return 'border-green';
      default:
        return '';
    }
  }

  formatDate(dateString: string | null | undefined): string {
    if (!dateString) return '—';
    try {
      const date = new Date(dateString);
      const day = date.getDate().toString().padStart(2, '0');
      const month = (date.getMonth() + 1).toString().padStart(2, '0');
      const year = date.getFullYear();
      return `${day}-${month}-${year}`;
    } catch (e) {
      return dateString;
    }
  }

  onWhatsAppRadioClick(e: MouseEvent, _card: PaymentDateCustomerCard): void {
    e.stopPropagation();
    if (!this.canChangeWhatsappDate) {
      this.permissionService.notifyRoleDenied('change WhatsApp status', 'whatsappDateChange');
    }
  }

  onCategoryRadioClick(e: MouseEvent, _card: PaymentDateCustomerCard): void {
    e.stopPropagation();
    if (!this.canEditCustomerCategory) {
      this.permissionService.notifyRoleDenied('edit customer category', 'customerCategoryEdit');
    }
  }


  savePaymentDate(customer: string, date: string): void {
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit due dates', 'paymentDateEdit');
      return;
    }
    const cleaned = date.trim();
    if (!isValidPaymentDateFormat(cleaned)) {
      this.notificationService.showError('Invalid date format. Use DD-MM.', 4000);
      const card = this.cards.find(c => c.customer === customer);
      this.dateEdits[customer] = card?.nextPaymentDate ?? '';
      this.cdr.markForCheck();
      return;
    }
    if (cleaned && isPaymentDatePast(cleaned)) {
      this.notificationService.showError('Payment date cannot be before today.', 4000);
      const card = this.cards.find(c => c.customer === customer);
      this.dateEdits[customer] = card?.nextPaymentDate ?? '';
      this.cdr.markForCheck();
      return;
    }
    this.api.updateNextPaymentDate(customer, cleaned)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          const card = this.cards.find(c => c.customer === customer);
          if (card) {
            card.nextPaymentDate = cleaned || null;
          }
          this.dateEdits[customer] = cleaned;
          this.updateFilteredCards();
          const customerDisplayName = customer.length > 30 ? customer.substring(0, 30) + '...' : customer;
          this.notificationService.showSuccess(
            cleaned ? `Payment date saved for ${customerDisplayName}` : `Payment date cleared for ${customerDisplayName}`,
            3000
          );
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.logout();
            return;
          }
          const customerDisplayName = customer.length > 30 ? customer.substring(0, 30) + '...' : customer;
          this.notificationService.showError(`Failed to update due date for ${customerDisplayName}`, 3000);
        }
      });
  }

  onWhatsAppStatusChange(card: PaymentDateCustomerCard, status: string): void {
    if (!card.customer) return;
    if (!this.canChangeWhatsappDate) {
      this.permissionService.notifyRoleDenied('change WhatsApp status', 'whatsappDateChange');
      this.whatsappStatuses[card.customer] = card.whatsAppStatus ?? 'not sent';
      this.cdr.markForCheck();
      return;
    }
    this.whatsappStatuses[card.customer] = status;
    this.api.updateWhatsAppStatus(card.customer, status)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          const foundCard = this.cards.find(c => c.customer === card.customer);
          if (foundCard) {
            foundCard.whatsAppStatus = status;
          }
          const customerDisplayName = card.customer.length > 30 ? card.customer.substring(0, 30) + '...' : card.customer;
          this.notificationService.showSuccess(`WhatsApp status updated for ${customerDisplayName}`, 3000);
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.logout();
            return;
          }
          const customerDisplayName = card.customer.length > 30 ? card.customer.substring(0, 30) + '...' : card.customer;
          this.notificationService.showError(`Failed to update WhatsApp status for ${customerDisplayName}`, 3000);
        }
      });
  }

  onCustomerCategoryChange(card: PaymentDateCustomerCard, category: string): void {
    if (!card.customer) return;
    if (!this.canEditCustomerCategory) {
      this.permissionService.notifyRoleDenied('edit customer category', 'customerCategoryEdit');
      this.customerCategories[card.customer] = card.customerCategory ?? 'A';
      this.cdr.markForCheck();
      return;
    }
    this.customerCategories[card.customer] = category;
    this.api.updateCustomerCategory(card.customer, category)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          const foundCard = this.cards.find(c => c.customer === card.customer);
          if (foundCard) {
            foundCard.customerCategory = category;
          }
          const customerDisplayName = card.customer.length > 30 ? card.customer.substring(0, 30) + '...' : card.customer;
          this.notificationService.showSuccess(`Customer category updated for ${customerDisplayName}`, 3000);
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.logout();
            return;
          }
          const customerDisplayName = card.customer.length > 30 ? card.customer.substring(0, 30) + '...' : card.customer;
          this.notificationService.showError(`Failed to update customer category for ${customerDisplayName}`, 3000);
        }
      });
  }

  getCustomerCategory(card: PaymentDateCustomerCard): 'semi-wholesale' | 'A' | 'B' | 'C' {
    return (this.customerCategories[card.customer || ''] || card.customerCategory || 'A') as 'semi-wholesale' | 'A' | 'B' | 'C';
  }

  getCustomerCategoryDisplay(card: PaymentDateCustomerCard): string {
    const category = this.getCustomerCategory(card);
    switch (category) {
      case 'semi-wholesale':
        return 'Semi-wholesale';
      case 'A':
      case 'B':
      case 'C':
        return category;
      default:
        return category;
    }
  }

  getCustomerCategoryBorderClass(card: PaymentDateCustomerCard): string {
    const category = this.getCustomerCategory(card);
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

  /** Derives place from customer name: text in brackets e.g. "Shree Ganesh Mens Wear (Nallasopara)" -> "Nallasopara" */
  getPlace(card: PaymentDateCustomerCard): string {
    const name = card.customer || '';
    const m = name.match(/\(([^)]+)\)/);
    return m ? m[1].trim() : '';
  }

  hasLocation(card: PaymentDateCustomerCard): boolean {
    return !!(card.address?.trim() || (card.latitude != null && card.longitude != null));
  }

  onFollowUpToggle(card: PaymentDateCustomerCard): void {
    if (!card.customer) return;
    if (!this.canChangeFollowUp) {
      this.permissionService.notifyRoleDenied('change follow-up flags', 'followUpChange');
      return;
    }
    const newValue = !this.followUpFlags[card.customer];
    this.followUpFlags[card.customer] = newValue;
    this.api.updateFollowUpFlag(card.customer, newValue)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          const foundCard = this.cards.find(c => c.customer === card.customer);
          if (foundCard) {
            foundCard.needsFollowUp = newValue;
          }
          const customerDisplayName = card.customer.length > 30 ? card.customer.substring(0, 30) + '...' : card.customer;
          this.notificationService.showSuccess(`Follow-up flag updated for ${customerDisplayName}`, 3000);
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.logout();
            return;
          }
          const customerDisplayName = card.customer.length > 30 ? card.customer.substring(0, 30) + '...' : card.customer;
          this.notificationService.showError(`Failed to update follow-up flag for ${customerDisplayName}`, 3000);
        }
      });
  }

  onRetainToggle(card: PaymentDateCustomerCard): void {
    const name = card.customer;
    if (!name) {
      return;
    }
    if (!this.canRetainCustomer) {
      this.permissionService.notifyRoleDenied('retain customers on Outstanding Due', 'customerRetainEdit');
      return;
    }
    if (this.retainToggleBusy[name]) {
      return;
    }

    const makingRetained = !card.retained;
    const shortName = name.length > 30 ? name.substring(0, 30) + '...' : name;
    this.retainToggleBusy[name] = true;
    card.retained = makingRetained;

    if (makingRetained) {
      this.api.retainCustomer(name)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (view) => {
            const foundCard = this.cards.find(c => c.customer === name);
            if (foundCard) {
              foundCard.retained = true;
            }
            if (view?.customerKey && !this.retainedCustomers.some(r => r.customerKey === view.customerKey)) {
              this.retainedCustomers = [...this.retainedCustomers, view];
            }
            this.updateFilteredCards();
            this.loadRetainedCustomers();
            this.notificationService.showSuccess(`${shortName} is now retained`);
            this.retainToggleBusy[name] = false;
          },
          error: (err: HttpErrorResponse) => {
            const foundCard = this.cards.find(c => c.customer === name);
            if (foundCard) {
              foundCard.retained = false;
            }
            this.updateFilteredCards();
            this.retainToggleBusy[name] = false;
            this.notificationService.showError(
              this.apiErrorMessage(err, 'Could not retain customer.')
            );
          }
        });
      return;
    }

    const match = this.findRetainedMatch(this.retainedCustomers, name);
    const customerKey = match?.customerKey || this.normalizeCustomerKey(name);
    if (!customerKey) {
      const foundCard = this.cards.find(c => c.customer === name);
      if (foundCard) {
        foundCard.retained = true;
      }
      this.updateFilteredCards();
      this.retainToggleBusy[name] = false;
      this.notificationService.showError('Could not unretain customer.');
      return;
    }

    this.api.unretainCustomer(customerKey)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          const foundCard = this.cards.find(c => c.customer === name);
          if (foundCard) {
            foundCard.retained = false;
            if ((foundCard.totalAmount ?? 0) === 0) {
              this.cards = this.cards.filter(c => c.customer !== name);
            }
          }
          this.retainedCustomers = this.retainedCustomers.filter(r => r.customerKey !== customerKey);
          this.updateFilteredCards();
          this.loadRetainedCustomers();
          this.notificationService.showSuccess(`${shortName} removed from retained`);
          this.retainToggleBusy[name] = false;
        },
        error: (err: HttpErrorResponse) => {
          const foundCard = this.cards.find(c => c.customer === name);
          if (foundCard) {
            foundCard.retained = true;
          }
          this.updateFilteredCards();
          this.retainToggleBusy[name] = false;
          this.notificationService.showError(
            this.apiErrorMessage(err, 'Could not unretain customer.')
          );
        }
      });
  }

  openCustomerDetails(card: PaymentDateCustomerCard): void {
    // SECURITY: Do NOT put sensitive data (customer names) in URL query parameters
    // Store in sessionStorage instead
    if (card.customer) {
      sessionStorage.setItem('openProject.selectedCustomer', card.customer);
      this.router.navigate(['/outstanding']);
    }
  }

  openWhatsApp(card: PaymentDateCustomerCard): void {
    const phone = formatPhoneForWhatsApp(card.phoneNumber);
    if (phone) {
      window.open(`https://wa.me/${phone}`, '_blank');
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

  toggleViewMode(): void {
    this.viewMode = this.viewMode === 'grid' ? 'list' : 'grid';
  }

  toggleSort(): void {
    if (this.sortOrder === 'desc') {
      this.sortOrder = 'asc';
    } else {
      if (this.sortBy === 'amount') {
        this.sortBy = 'name';
        this.sortOrder = 'asc';
      } else if (this.sortBy === 'name') {
        this.sortBy = 'date';
        this.sortOrder = 'desc';
      } else {
        this.sortBy = 'amount';
        this.sortOrder = 'desc';
      }
    }
    this.updateFilteredCards();
  }

  downloadExcel(): void {
    const cols = [
      'Customer',
      'Phone',
      'Amount',
      '1-45 Days',
      '46-85 Days',
      '85+ Days',
      'Category',
      'Last Invoice Date',
      'Due Date',
      'WhatsApp Status',
      'Follow Up',
      'Retained',
    ] as const;
    const totalCols = cols.length;
    const watermarkRow = buildExcelWatermarkRow(totalCols);
    const headerRow = [...cols];
    const bodyRows = this.filteredCards.map(card => [
      card.customer,
      card.phoneNumber ? formatPhoneDisplay(card.phoneNumber) : '',
      formatInrForExcel(this.getDisplayAmount(card)),
      formatInrForExcel(card.withinAmount ?? 0),
      formatInrForExcel(card.midAmount ?? 0),
      formatInrForExcel(card.beyondAmount ?? 0),
      card.customerCategory || '',
      card.lastOrderDate || '',
      card.nextPaymentDate || '',
      card.whatsAppStatus || 'not sent',
      card.needsFollowUp ? 'Yes' : 'No',
      card.retained ? 'Yes' : 'No',
    ]);
    const ws = XLSX.utils.aoa_to_sheet([watermarkRow, headerRow, ...bodyRows]);
    const colWidths = [
      { wch: 28 },
      { wch: 14 },
      { wch: 18 },
      { wch: 14 },
      { wch: 14 },
      { wch: 14 },
      { wch: 14 },
      { wch: 16 },
      { wch: 14 },
      { wch: 16 },
      { wch: 10 },
      { wch: 10 },
    ];
    ws['!cols'] = colWidths;
    if (!ws['!merges']) {
      ws['!merges'] = [];
    }
    ws['!merges'].push({ s: { r: 0, c: 0 }, e: { r: 0, c: totalCols - 1 } });

    const wb = XLSX.utils.book_new();
    const sheetName = 'Outstanding Due';
    XLSX.utils.book_append_sheet(wb, ws, sheetName);
    setExcelPrintTitleTopRow(wb, sheetName);
    XLSX.writeFile(wb, `outstanding-due-${new Date().toISOString().split('T')[0]}.xlsx`);
  }

  downloadPDF(): void {
    void this.downloadOutstandingDuePdf();
  }

  private async downloadOutstandingDuePdf(): Promise<void> {
    const doc = new jsPDF();
    try {
      await ensurePdfUnicodeFonts(doc);
    } catch {
      this.notificationService.showError('Could not load PDF fonts. Refresh the page and try again.');
      return;
    }
    autoTable(doc, {
      head: [['Customer', 'Phone', 'Amount', '1-45', '46-85', '85+', 'Category', 'Last Invoice', 'Due Date', 'Status', 'Retained']],
      body: this.filteredCards.map(card => [
        card.customer || '',
        card.phoneNumber ? formatPhoneDisplay(card.phoneNumber) : '',
        formatInrForPdf(this.getDisplayAmount(card)),
        formatInrForPdf(card.withinAmount ?? 0),
        formatInrForPdf(card.midAmount ?? 0),
        formatInrForPdf(card.beyondAmount ?? 0),
        card.customerCategory || '',
        card.lastOrderDate || '',
        card.nextPaymentDate || '',
        card.whatsAppStatus || 'not sent',
        card.retained ? 'Yes' : 'No',
      ]),
      theme: 'striped',
      styles: { font: PDF_UNICODE_FONT, fontStyle: 'normal' },
      headStyles: { fillColor: [37, 99, 235], font: PDF_UNICODE_FONT, fontStyle: 'bold' },
      didDrawPage: () => {
        addWatermark(doc);
      },
    });
    doc.save(`outstanding-due-${new Date().toISOString().split('T')[0]}.pdf`);
  }

  loadCategoryCreditLimits(): void {
    this.api.getCustomerCreditLimitDefaults().subscribe({
      next: (res) => {
        const limits = res.limits ?? {};
        this.categoryLimitEdits = {
          'semi-wholesale': String(limits['semi-wholesale'] ?? ''),
          A: String(limits['A'] ?? ''),
          B: String(limits['B'] ?? ''),
          C: String(limits['C'] ?? '')
        };
      }
    });
  }

  saveCategoryCreditLimits(): void {
    if (!this.isAdmin) {
      return;
    }
    const limits: Record<string, number> = {};
    for (const key of ['semi-wholesale', 'A', 'B', 'C']) {
      const raw = (this.categoryLimitEdits[key] ?? '').trim().replace(/,/g, '');
      const parsed = Number(raw);
      if (!raw || !Number.isFinite(parsed) || parsed < 0) {
        this.notificationService.showError(`Enter a valid limit for ${key}.`, 4000);
        return;
      }
      limits[key] = parsed;
    }
    this.savingCategoryLimits = true;
    this.api.updateCustomerCreditLimitDefaults(limits).subscribe({
      next: (res) => {
        this.savingCategoryLimits = false;
        const saved = res.limits ?? limits;
        this.categoryLimitEdits = {
          'semi-wholesale': String(saved['semi-wholesale'] ?? ''),
          A: String(saved['A'] ?? ''),
          B: String(saved['B'] ?? ''),
          C: String(saved['C'] ?? '')
        };
        this.loadData();
        this.notificationService.showSuccess('Category credit limits saved.', 3000);
      },
      error: () => {
        this.savingCategoryLimits = false;
        this.notificationService.showError('Failed to save category limits.', 3000);
      }
    });
  }

  private saveFilters(): void {
    try {
      localStorage.setItem(this.filterStorageKey, JSON.stringify(this.filters));
    } catch (e) {
      // Silently fail - localStorage may be disabled or quota exceeded
    }
  }

  private restoreFilters(): void {
    try {
      const saved = localStorage.getItem(this.filterStorageKey);
      if (saved) {
        const parsed = JSON.parse(saved);
        // Migrate old single place to places array
        if (parsed.place !== undefined && parsed.places === undefined) {
          parsed.places = parsed.place ? [parsed.place] : [];
        }
        if (!Array.isArray(parsed.places)) parsed.places = [];
        if (parsed.retained !== 'all' && parsed.retained !== 'yes' && parsed.retained !== 'no') {
          parsed.retained = 'all';
        }
        if (parsed.creditLimit !== 'all' && parsed.creditLimit !== 'over' && parsed.creditLimit !== 'within' && parsed.creditLimit !== 'none') {
          parsed.creditLimit = 'all';
        }
        const paymentDateModes: PaymentDateFilterMode[] = ['all', 'past', 'today', 'tomorrow', 'future', 'none'];
        if (!paymentDateModes.includes(parsed.paymentDate)) {
          parsed.paymentDate = 'all';
        }
        this.filters = { ...this.filters, ...parsed, places: parsed.places || [] };
      }
    } catch (e) {
      // Silently fail - localStorage may be disabled or corrupted
    }
  }

  loadExcludedCustomers(): void {
    this.api.getExcludedCustomers()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (list) => {
          this.excludedCustomers = list ?? [];
        },
        error: () => {
          this.excludedCustomers = [];
        }
      });
  }

  loadRetainedCustomers(): void {
    this.api.getRetainedCustomers()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (list) => {
          this.retainedCustomers = list ?? [];
        },
        error: () => {
          this.retainedCustomers = [];
        }
      });
  }

  loadDriveSyncStatus(): void {
    this.api.getDrivePaymentDateSyncStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.driveSync = status;
          if (status?.configured && (status.unmatched > 0 || status.invalidDates > 0 || status.lastStatus === 'failed')) {
            this.showDriveSyncPanel = true;
          }
        },
        error: () => {
          this.driveSync = null;
        }
      });
  }

  toggleDriveSyncPanel(): void {
    this.showDriveSyncPanel = !this.showDriveSyncPanel;
    if (this.showDriveSyncPanel) {
      this.showIgnoredPanel = false;
      this.showRetainedPanel = false;
      this.loadDriveSyncStatus();
    }
  }

  runDriveSync(): void {
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('sync due dates from Drive', 'paymentDateEdit');
      return;
    }
    if (this.driveSyncing) {
      return;
    }
    this.driveSyncing = true;
    this.api.runDrivePaymentDateSync()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.driveSync = status;
          this.driveSyncing = false;
          this.showDriveSyncPanel = true;
          if (status.lastStatus === 'success' || status.lastStatus === 'pushed') {
            this.notificationService.showSuccess(status.lastMessage || 'Due dates synced with Drive Excel.');
            this.reloadPageAfterSync();
          } else if (status.lastStatus === 'skipped') {
            this.notificationService.showSuccess(status.lastMessage || 'Drive file has not changed.');
            this.reloadPageAfterSync();
          } else {
            this.notificationService.showError(status.lastMessage || 'Drive sync failed.', 4000);
          }
        },
        error: (err: HttpErrorResponse) => {
          this.driveSyncing = false;
          if (err.error && typeof err.error === 'object' && 'lastStatus' in err.error) {
            this.driveSync = err.error as DrivePaymentDateSyncStatus;
            const status = this.driveSync.lastStatus;
            const message = this.driveSync.lastMessage || 'Drive sync failed.';
            if (err.status === 409) {
              this.notificationService.showError('A Drive sync is already running.', 4000);
              return;
            }
            if (err.status === 502 || err.status === 503 || status === 'failed' || status === 'push-failed') {
              this.notificationService.showError(message, 5000);
              return;
            }
          }
          if (err.status === 403) {
            this.permissionService.notifyRoleDenied('sync due dates from Drive', 'paymentDateEdit');
            return;
          }
          this.notificationService.showError(this.apiErrorMessage(err, 'Drive sync failed.'), 4000);
        }
      });
  }

  private reloadPageAfterSync(): void {
    window.setTimeout(() => window.location.reload(), 500);
  }

  toggleIgnoredPanel(): void {
    this.showIgnoredPanel = !this.showIgnoredPanel;
    if (this.showIgnoredPanel) {
      this.showRetainedPanel = false;
      this.showDriveSyncPanel = false;
      this.loadExcludedCustomers();
    }
  }

  toggleRetainedPanel(): void {
    this.showRetainedPanel = !this.showRetainedPanel;
    if (this.showRetainedPanel) {
      this.showIgnoredPanel = false;
      this.showDriveSyncPanel = false;
      this.loadRetainedCustomers();
    }
  }

  private apiErrorMessage(err: HttpErrorResponse, fallback: string): string {
    const body = err?.error;
    if (body && typeof body === 'object' && typeof body.error === 'string' && body.error.trim()) {
      return body.error;
    }
    return fallback;
  }

  excludeCustomerByName(): void {
    const name = this.ignoreCustomerInput.trim();
    if (!name) {
      return;
    }
    if (!this.canExcludeCustomer) {
      this.permissionService.notifyRoleDenied('ignore customers on Outstanding Due', 'customerExcludeEdit');
      return;
    }
    this.showIgnoreNameSuggestions = false;
    this.ignoreNameSuggestions = [];
    this.api.excludeCustomer(name)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notificationService.showSuccess(`${name} is now ignored`);
          this.ignoreCustomerInput = '';
          this.loadExcludedCustomers();
          this.loadData();
        },
        error: (err: HttpErrorResponse) => {
          this.notificationService.showError(
            this.apiErrorMessage(err, 'Could not ignore customer. Check the name and try again.')
          );
        }
      });
  }

  onIgnoreNameInput(): void {
    this.scheduleNameSuggestions('ignore');
  }

  selectIgnoreNameSuggestion(name: string): void {
    this.ignoreCustomerInput = name;
    this.ignoreNameSuggestions = [];
    this.showIgnoreNameSuggestions = false;
  }

  restoreExcludedCustomer(customerKey: string, displayName?: string | null): void {
    if (!this.canExcludeCustomer) {
      this.permissionService.notifyRoleDenied('restore ignored customers', 'customerExcludeEdit');
      return;
    }
    this.api.restoreExcludedCustomer(customerKey)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notificationService.showSuccess(`${displayName || customerKey} restored`);
          this.loadExcludedCustomers();
          this.loadData();
        },
        error: (err: HttpErrorResponse) => {
          this.notificationService.showError(
            this.apiErrorMessage(err, 'Could not restore customer.')
          );
        }
      });
  }

  retainCustomerByName(): void {
    const name = this.retainCustomerInput.trim();
    if (!name) {
      return;
    }
    if (!this.canRetainCustomer) {
      this.permissionService.notifyRoleDenied('retain customers on Outstanding Due', 'customerRetainEdit');
      return;
    }
    this.showRetainNameSuggestions = false;
    this.retainNameSuggestions = [];
    this.api.retainCustomer(name)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notificationService.showSuccess(`${name} is now retained`);
          this.retainCustomerInput = '';
          this.loadRetainedCustomers();
          this.loadData();
        },
        error: (err: HttpErrorResponse) => {
          this.notificationService.showError(
            this.apiErrorMessage(err, 'Could not retain customer. Check the name and try again.')
          );
        }
      });
  }

  onRetainNameInput(): void {
    this.scheduleNameSuggestions('retain');
  }

  selectRetainNameSuggestion(name: string): void {
    this.retainCustomerInput = name;
    this.retainNameSuggestions = [];
    this.showRetainNameSuggestions = false;
  }

  private scheduleNameSuggestions(kind: 'ignore' | 'retain'): void {
    const query = (kind === 'ignore' ? this.ignoreCustomerInput : this.retainCustomerInput).trim();

    if (kind === 'ignore') {
      if (this.ignoreSuggestTimer) clearTimeout(this.ignoreSuggestTimer);
    } else if (this.retainSuggestTimer) {
      clearTimeout(this.retainSuggestTimer);
    }

    if (query.length < 3) {
      if (kind === 'ignore') {
        this.ignoreNameSuggestions = [];
        this.ignoreNameSuggestQuery = '';
        this.showIgnoreNameSuggestions = false;
      } else {
        this.retainNameSuggestions = [];
        this.retainNameSuggestQuery = '';
        this.showRetainNameSuggestions = false;
      }
      return;
    }

    if (kind === 'ignore') {
      this.ignoreNameSuggestQuery = query;
      this.ignoreNameSuggestions = [];
    } else {
      this.retainNameSuggestQuery = query;
      this.retainNameSuggestions = [];
    }

    const timer = window.setTimeout(() => {
      this.api.getCustomerSuggestions(query, this.nameSuggestLimit)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (suggestions) => {
            const page = suggestions ?? [];
            if (kind === 'ignore') {
              if (this.ignoreNameSuggestQuery !== query) {
                return;
              }
              this.ignoreNameSuggestions = page;
              this.showIgnoreNameSuggestions = page.length > 0;
            } else {
              if (this.retainNameSuggestQuery !== query) {
                return;
              }
              this.retainNameSuggestions = page;
              this.showRetainNameSuggestions = page.length > 0;
            }
          },
          error: () => {
            if (kind === 'ignore') {
              this.ignoreNameSuggestions = [];
              this.ignoreNameSuggestQuery = '';
              this.showIgnoreNameSuggestions = false;
            } else {
              this.retainNameSuggestions = [];
              this.retainNameSuggestQuery = '';
              this.showRetainNameSuggestions = false;
            }
          }
        });
    }, 300);

    if (kind === 'ignore') {
      this.ignoreSuggestTimer = timer;
    } else {
      this.retainSuggestTimer = timer;
    }
  }

  unretainCustomer(customerKey: string, displayName?: string | null): void {
    if (!this.canRetainCustomer) {
      this.permissionService.notifyRoleDenied('unretain customers', 'customerRetainEdit');
      return;
    }
    this.api.unretainCustomer(customerKey)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notificationService.showSuccess(`${displayName || customerKey} removed from retained`);
          this.loadRetainedCustomers();
          this.loadData();
        },
        error: (err: HttpErrorResponse) => {
          this.notificationService.showError(
            this.apiErrorMessage(err, 'Could not unretain customer.')
          );
        }
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

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}


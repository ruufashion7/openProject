import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, takeUntil } from 'rxjs';
import { ApiService, PaymentDateCustomerCard } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
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

interface FilterState {
  paymentDate: 'all' | 'past' | 'today' | 'future' | 'none';
  whatsappStatus: 'all' | 'not sent' | 'sent' | 'delivered';
  customerCategory: 'all' | 'semi-wholesale' | 'A' | 'B' | 'C';
  followUp: 'all' | 'needed' | 'not-needed';
  orderDate: 'all' | '0-45' | '46-85' | '85+' | 'custom';
  places: string[];
  orderDateFrom?: string;
  orderDateTo?: string;
}

@Component({
  selector: 'app-payment-dates',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './payment-dates.component.html',
  styleUrl: './payment-dates.component.css'
})
export class PaymentDatesComponent implements OnInit, OnDestroy {
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
    orderDate: 'all',
    places: []
  };

  /** Autocomplete input value for place */
  placeSearchQuery = '';
  placeSuggestionsOpen = false;
  private placeBlurTimer: ReturnType<typeof setTimeout> | null = null;

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

  /** Full list of place suggestions (alphabetically sorted), excluding already selected. Used for scroll-to-load. */
  get placeSuggestions(): string[] {
    const unselected = this.placeOptions.filter(p => !this.filters.places.includes(p));
    const q = this.placeSearchQuery.trim().toLowerCase();
    if (!q) return unselected;
    return unselected
      .filter(p => p.toLowerCase().includes(q))
      .sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
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
  
  // Permissions
  canEditPaymentDate = false;
  canChangeWhatsappDate = false;
  canChangeFollowUp = false;
  canEditCustomerCategory = false;
  
  // Timers
  private saveTimers: Record<string, number> = {};
  private searchTimer: any = null;
  private readonly filterStorageKey = 'paymentDatesV2.filters';
  
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

        this.restoreFilters();
        this.loadData();
      });
  }

  ngOnDestroy(): void {
    // Clear timers
    Object.values(this.saveTimers).forEach(timer => clearTimeout(timer));
    if (this.searchTimer) clearTimeout(this.searchTimer);
    if (this.placeBlurTimer) clearTimeout(this.placeBlurTimer);
    // Complete destroy subject to cleanup subscriptions
    this.destroy$.next();
    this.destroy$.complete();
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
          this.loadPaymentDates();
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

  private loadPaymentDates(): void {
    this.api.getPaymentDates()
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
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.status = 'failed';
            this.message = 'Session expired. Please login again.';
            this.logout();
            return;
          }
          this.status = 'failed';
          this.message = 'Unable to load payment dates.';
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
        this.dateEdits[card.customer] = card.nextPaymentDate ?? '';
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
    let filtered = [...this.cards];
    
    // Search filter
    if (this.searchQuery.trim()) {
      const query = this.searchQuery.toLowerCase().trim();
      const normalizedQuery = query.replace(/\D/g, '');
      filtered = filtered.filter(card => {
        const name = (card.customer || '').toLowerCase();
        const phone = (card.phoneNumber || '').replace(/\D/g, '');
        const nameMatch = name.includes(query);
        const phoneMatch = normalizedQuery && phone && (phone.includes(normalizedQuery) || normalizedQuery.includes(phone));
        return nameMatch || phoneMatch;
      });
    }
    
    // Payment date filter
    if (this.filters.paymentDate !== 'all') {
      filtered = filtered.filter(card => {
        const date = card.nextPaymentDate;
        if (!date || date.trim() === '') {
          return this.filters.paymentDate === 'none';
        }
        const today = new Date();
        today.setHours(0, 0, 0, 0); // Normalize to midnight to avoid timezone issues
        const [day, month] = date.split('-').map(Number);
        const paymentDate = new Date(today.getFullYear(), month - 1, day);
        paymentDate.setHours(0, 0, 0, 0); // Normalize to midnight
        if (paymentDate < new Date(today.getFullYear(), today.getMonth(), today.getDate())) {
          paymentDate.setFullYear(today.getFullYear() + 1);
        }
        const diffDays = Math.floor((paymentDate.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
        if (this.filters.paymentDate === 'past') return diffDays < 0;
        if (this.filters.paymentDate === 'today') return diffDays === 0;
        if (this.filters.paymentDate === 'future') return diffDays > 0;
        return false;
      });
    }
    
    // WhatsApp status filter
    if (this.filters.whatsappStatus !== 'all') {
      filtered = filtered.filter(card => 
        (card.whatsAppStatus || 'not sent') === this.filters.whatsappStatus
      );
    }
    
    // Customer category filter
    if (this.filters.customerCategory !== 'all') {
      filtered = filtered.filter(card => {
        const category = this.getCustomerCategory(card);
        return category === this.filters.customerCategory;
      });
    }
    
    // Follow-up filter
    if (this.filters.followUp !== 'all') {
      filtered = filtered.filter(card => {
        const needsFollowUp = card.needsFollowUp ?? false;
        return this.filters.followUp === 'needed' ? needsFollowUp : !needsFollowUp;
      });
    }

    // Place filter (multiple places: show if card's place is in selected list)
    if (this.filters.places.length > 0) {
      filtered = filtered.filter(card => {
        const place = this.getPlace(card);
        return place !== '' && this.filters.places.includes(place);
      });
    }
    
    // Order date filter
    if (this.filters.orderDate !== 'all') {
      filtered = filtered.filter(card => {
        if (!card.lastOrderDate) return false;
        const orderDate = new Date(card.lastOrderDate);
        orderDate.setHours(0, 0, 0, 0); // Normalize to midnight
        const today = new Date();
        today.setHours(0, 0, 0, 0); // Normalize to midnight
        const diffDays = Math.floor((today.getTime() - orderDate.getTime()) / (1000 * 60 * 60 * 24));
        if (this.filters.orderDate === '0-45') return diffDays <= 45;
        if (this.filters.orderDate === '46-85') return diffDays > 45 && diffDays <= 85;
        if (this.filters.orderDate === '85+') return diffDays > 85;
        return true;
      });
    }
    
    // Sort
    filtered.sort((a, b) => {
      let comparison = 0;
      if (this.sortBy === 'amount') {
        comparison = a.totalAmount - b.totalAmount;
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
  }

  updateTotals(): void {
    // Exclude "Total" from customer count
    const validCustomers = this.filteredCards.filter(card => {
      const customerName = (card.customer || '').toLowerCase().trim();
      return customerName !== 'total';
    });
    
    this.totalAmount = this.filteredCards.reduce((sum, card) => sum + card.totalAmount, 0);
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
        const phone = (card.phoneNumber || '').replace(/\D/g, '');
        const nameMatch = name.includes(query);
        const phoneMatch = normalizedQuery && phone && (phone.includes(normalizedQuery) || normalizedQuery.includes(phone));
        return nameMatch || phoneMatch;
      })
      .slice(0, 8)
      .map(card => ({
        name: card.customer || '',
        phone: card.phoneNumber || ''
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
      orderDate: 'all',
      places: []
    };
    this.saveFilters();
    this.updateFilteredCards();
  }

  hasActiveFilters(): boolean {
    return this.filters.paymentDate !== 'all' ||
           this.filters.whatsappStatus !== 'all' ||
           this.filters.customerCategory !== 'all' ||
           this.filters.followUp !== 'all' ||
           this.filters.orderDate !== 'all' ||
           this.filters.places.length > 0;
  }

  addPlaceFromDropdown(place: string): void {
    if (!place || this.filters.places.includes(place)) return;
    this.filters.places = [...this.filters.places, place].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
    this.placeSearchQuery = '';
    this.placeSuggestionsOpen = false;
    this.saveFilters();
    this.updateFilteredCards();
  }

  onPlaceInputFocus(): void {
    if (this.placeBlurTimer) {
      clearTimeout(this.placeBlurTimer);
      this.placeBlurTimer = null;
    }
    this.placeSuggestionsVisibleCount = this.PLACE_PAGE_SIZE;
    this.placeSuggestionsOpen = true;
  }

  onPlaceSearchChange(): void {
    this.placeSuggestionsVisibleCount = this.PLACE_PAGE_SIZE;
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

  onPlaceInputBlur(): void {
    this.placeBlurTimer = setTimeout(() => {
      this.placeSuggestionsOpen = false;
      this.placeBlurTimer = null;
    }, 200);
  }

  onPlaceKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && this.placeSuggestionsSlice.length > 0) {
      event.preventDefault();
      this.addPlaceFromDropdown(this.placeSuggestionsSlice[0]);
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

  getPaymentDateTone(card: PaymentDateCustomerCard): 'past' | 'today' | 'future' | 'none' {
    const date = this.dateEdits[card.customer || ''] || card.nextPaymentDate || '';
    if (!date || date.trim() === '') return 'none';
    const today = new Date();
    today.setHours(0, 0, 0, 0); // Normalize to midnight to avoid timezone issues
    const [day, month] = date.split('-').map(Number);
    const paymentDate = new Date(today.getFullYear(), month - 1, day);
    paymentDate.setHours(0, 0, 0, 0); // Normalize to midnight
    if (paymentDate < new Date(today.getFullYear(), today.getMonth(), today.getDate())) {
      paymentDate.setFullYear(today.getFullYear() + 1);
    }
    const diffDays = Math.floor((paymentDate.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
    if (diffDays < 0) return 'past';
    if (diffDays === 0) return 'today';
    return 'future';
  }

  getPaymentDateBorderClass(card: PaymentDateCustomerCard): string {
    const tone = this.getPaymentDateTone(card);
    switch (tone) {
      case 'past':
        return 'border-red';
      case 'today':
        return 'border-yellow';
      case 'future':
        return 'border-green';
      case 'none':
      default:
        return 'border-grey';
    }
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

  formatDate(dateString: string): string {
    if (!dateString) return '';
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

  onPaymentDateFocusDeny(card: PaymentDateCustomerCard, e: FocusEvent): void {
    if (!this.canEditPaymentDate) {
      (e.target as HTMLInputElement)?.blur();
      this.permissionService.notifyRoleDenied('edit payment dates', 'paymentDateEdit');
    }
  }

  onDateChange(card: PaymentDateCustomerCard, event: any): void {
    if (!card.customer) return;
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit payment dates', 'paymentDateEdit');
      this.dateEdits[card.customer] = card.nextPaymentDate ?? '';
      this.cdr.markForCheck();
      return;
    }
    const date = event.target?.value || '';
    this.dateEdits[card.customer] = date;
    if (this.saveTimers[card.customer]) {
      clearTimeout(this.saveTimers[card.customer]);
    }
    this.saveTimers[card.customer] = window.setTimeout(() => {
      this.savePaymentDate(card.customer!, date);
    }, 1000);
  }

  savePaymentDate(customer: string, date: string): void {
    if (!this.canEditPaymentDate) {
      this.permissionService.notifyRoleDenied('edit payment dates', 'paymentDateEdit');
      return;
    }
    this.api.updateNextPaymentDate(customer, date)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          const card = this.cards.find(c => c.customer === customer);
          if (card) {
            card.nextPaymentDate = date;
          }
          const customerDisplayName = customer.length > 30 ? customer.substring(0, 30) + '...' : customer;
          this.notificationService.showSuccess(`Payment date updated for ${customerDisplayName}`, 3000);
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.logout();
            return;
          }
          const customerDisplayName = customer.length > 30 ? customer.substring(0, 30) + '...' : customer;
          this.notificationService.showError(`Failed to update payment date for ${customerDisplayName}`, 3000);
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

  openCustomerDetails(card: PaymentDateCustomerCard): void {
    // SECURITY: Do NOT put sensitive data (customer names) in URL query parameters
    // Store in sessionStorage instead
    if (card.customer) {
      sessionStorage.setItem('openProject.selectedCustomer', card.customer);
      this.router.navigate(['/outstanding']);
    }
  }

  openWhatsApp(card: PaymentDateCustomerCard): void {
    if (card.phoneNumber) {
      // Remove any non-digit characters and ensure it starts with country code
      const phone = card.phoneNumber.replace(/\D/g, '');
      if (phone) {
        // Open WhatsApp with the phone number
        const whatsappUrl = `https://wa.me/${phone}`;
        window.open(whatsappUrl, '_blank');
      }
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
      'Category',
      'Last Order Date',
      'Payment Date',
      'WhatsApp Status',
      'Follow Up',
    ] as const;
    const totalCols = cols.length;
    const watermarkRow = buildExcelWatermarkRow(totalCols);
    const headerRow = [...cols];
    const bodyRows = this.filteredCards.map(card => [
      card.customer,
      card.phoneNumber || '',
      formatInrForExcel(card.totalAmount),
      card.customerCategory || '',
      card.lastOrderDate || '',
      card.nextPaymentDate || '',
      card.whatsAppStatus || 'not sent',
      card.needsFollowUp ? 'Yes' : 'No',
    ]);
    const ws = XLSX.utils.aoa_to_sheet([watermarkRow, headerRow, ...bodyRows]);
    const colWidths = [
      { wch: 28 },
      { wch: 14 },
      { wch: 18 },
      { wch: 14 },
      { wch: 16 },
      { wch: 14 },
      { wch: 16 },
      { wch: 10 },
    ];
    ws['!cols'] = colWidths;
    if (!ws['!merges']) {
      ws['!merges'] = [];
    }
    ws['!merges'].push({ s: { r: 0, c: 0 }, e: { r: 0, c: totalCols - 1 } });

    const wb = XLSX.utils.book_new();
    const sheetName = 'Payment Dates';
    XLSX.utils.book_append_sheet(wb, ws, sheetName);
    setExcelPrintTitleTopRow(wb, sheetName);
    XLSX.writeFile(wb, `payment-dates-${new Date().toISOString().split('T')[0]}.xlsx`);
  }

  downloadPDF(): void {
    void this.downloadPaymentDatesPdf();
  }

  private async downloadPaymentDatesPdf(): Promise<void> {
    const doc = new jsPDF();
    try {
      await ensurePdfUnicodeFonts(doc);
    } catch {
      this.notificationService.showError('Could not load PDF fonts. Refresh the page and try again.');
      return;
    }
    autoTable(doc, {
      head: [['Customer', 'Phone', 'Amount', 'Category', 'Last Order Date', 'Payment Date', 'Status']],
      body: this.filteredCards.map(card => [
        card.customer || '',
        card.phoneNumber || '',
        formatInrForPdf(card.totalAmount),
        card.customerCategory || '',
        card.lastOrderDate || '',
        card.nextPaymentDate || '',
        card.whatsAppStatus || 'not sent',
      ]),
      theme: 'striped',
      styles: { font: PDF_UNICODE_FONT, fontStyle: 'normal' },
      headStyles: { fillColor: [37, 99, 235], font: PDF_UNICODE_FONT, fontStyle: 'bold' },
      didDrawPage: () => {
        addWatermark(doc);
      },
    });
    doc.save(`payment-dates-${new Date().toISOString().split('T')[0]}.pdf`);
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
        this.filters = { ...this.filters, ...parsed, places: parsed.places || [] };
      }
    } catch (e) {
      // Silently fail - localStorage may be disabled or corrupted
    }
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}


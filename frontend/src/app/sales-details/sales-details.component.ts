import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, takeUntil } from 'rxjs';
import { ApiService, SalesInvoiceEntry } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';

@Component({
  selector: 'app-sales-details',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './sales-details.component.html',
  styleUrl: './sales-details.component.css'
})
export class SalesDetailsComponent implements OnInit, OnDestroy {
  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';
  ready = false;
  invoices: SalesInvoiceEntry[] = [];
  
  // Basic Filters
  customerFilter = '';
  phoneFilter = '';
  voucherNoFilter = '';
  
  // Date Filters
  dateFromFilter = '';
  dateToFilter = '';
  datePreset: 'none' | 'today' | 'last7' | 'last30' | 'last90' | 'thisMonth' | 'lastMonth' = 'none';
  
  // Amount Filters
  receivedAmountMin = '';
  receivedAmountMax = '';
  currentDueMin = '';
  currentDueMax = '';
  
  // Ageing Days Filters
  ageingDaysMin = '';
  ageingDaysMax = '';
  ageingBucket: 'none' | '1-45' | '46-85' | '90+' = 'none';
  
  // Total Amount Filters
  totalAmountMin = '';
  totalAmountMax = '';
  
  // Year/Month/Quarter Filters
  yearFilter = '';
  monthFilter = '';
  quarterFilter = '';
  
  // Status Filter
  statusFilter: 'all' | 'paid' | 'unpaid' | 'partial' = 'all';
  
  // Sorting
  sortBy: string = '';
  sortOrder: 'asc' | 'desc' = 'asc';
  
  // Autocomplete suggestions
  customerSuggestions: string[] = [];
  phoneSuggestions: string[] = [];
  voucherSuggestions: string[] = [];
  
  // Timers for debouncing
  private customerTimer?: number;
  private phoneTimer?: number;
  private voucherTimer?: number;
  
  // Subscription management
  private destroy$ = new Subject<void>();
  
  // Pagination
  currentPage = 0;
  pageSize = 15;
  totalItems = 0;
  hasMorePages = true;
  
  // Advanced filters visibility
  showAdvancedFilters = false;

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router,
    private permissionService: PermissionService,
    private notificationService: NotificationService
  ) {}

  ngOnInit(): void {
    if (!this.permissionService.canAccessInvoicePage()) {
      this.notificationService.showPermissionError();
      this.router.navigateByUrl('/welcome');
      return;
    }

    this.status = 'loading';
    this.api.getUploadStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.ready = status.ready ?? (status.hasDetailed && status.hasReceivable);
          this.status = 'idle';
          if (this.ready) {
            this.loadInvoices();
          } else {
            this.message = 'Latest uploads not available.';
          }
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

  loadInvoices(): void {
    this.status = 'loading';
    
    // Parse amount filters
    const receivedAmountMin = this.receivedAmountMin ? parseFloat(this.receivedAmountMin) : undefined;
    const receivedAmountMax = this.receivedAmountMax ? parseFloat(this.receivedAmountMax) : undefined;
    const currentDueMin = this.currentDueMin ? parseFloat(this.currentDueMin) : undefined;
    const currentDueMax = this.currentDueMax ? parseFloat(this.currentDueMax) : undefined;
    
    // Parse ageing days filters
    const ageingDaysMin = this.ageingDaysMin ? parseInt(this.ageingDaysMin, 10) : undefined;
    const ageingDaysMax = this.ageingDaysMax ? parseInt(this.ageingDaysMax, 10) : undefined;
    const ageingBucket = this.ageingBucket !== 'none' ? this.ageingBucket : undefined;
    
    // Parse total amount filters
    const totalAmountMin = this.totalAmountMin ? parseFloat(this.totalAmountMin) : undefined;
    const totalAmountMax = this.totalAmountMax ? parseFloat(this.totalAmountMax) : undefined;
    
    // Parse year/month/quarter filters
    const year = this.yearFilter ? parseInt(this.yearFilter, 10) : undefined;
    const month = this.monthFilter ? parseInt(this.monthFilter, 10) : undefined;
    const quarter = this.quarterFilter ? parseInt(this.quarterFilter, 10) : undefined;
    
    // Status filter
    const status = this.statusFilter !== 'all' ? this.statusFilter : undefined;
    
    // Sorting
    const sortBy = this.sortBy || undefined;
    const sortOrder = this.sortBy ? this.sortOrder : undefined;
    
    this.api.getSalesInvoices({
      customer: this.customerFilter || undefined,
      phone: this.phoneFilter || undefined,
      voucherNo: this.voucherNoFilter || undefined,
      dateFrom: this.dateFromFilter || undefined,
      dateTo: this.dateToFilter || undefined,
      receivedAmountMin: receivedAmountMin,
      receivedAmountMax: receivedAmountMax,
      currentDueMin: currentDueMin,
      currentDueMax: currentDueMax,
      ageingDaysMin: ageingDaysMin,
      ageingDaysMax: ageingDaysMax,
      ageingBucket: ageingBucket,
      totalAmountMin: totalAmountMin,
      totalAmountMax: totalAmountMax,
      year: year,
      month: month,
      quarter: quarter,
      status: status,
      sortBy: sortBy,
      sortOrder: sortOrder,
      page: this.currentPage,
      size: this.pageSize
    })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.invoices = response.content;
          this.totalItems = response.totalElements;
          this.hasMorePages = this.currentPage < response.totalPages - 1;
          this.status = 'idle';
          this.message = this.invoices.length ? '' : 'No invoices found.';
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 401) {
            this.status = 'failed';
            this.message = 'Session expired. Please login again.';
            this.logout();
            return;
          }
          this.status = 'failed';
          this.message = 'Unable to load sales invoices.';
        }
      });
  }

  onCustomerFilterChange(value: string): void {
    if (!this.ready) return;
    this.customerFilter = value;
    if (this.customerTimer) {
      window.clearTimeout(this.customerTimer);
    }
    if (value.trim().length < 3) {
      this.customerSuggestions = [];
      return;
    }
    this.customerTimer = window.setTimeout(() => {
      this.api.getCustomerSuggestions(value.trim(), 20)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (suggestions) => {
            this.customerSuggestions = suggestions;
          },
          error: () => {
            this.customerSuggestions = [];
          }
        });
    }, 300);
  }

  onPhoneFilterChange(value: string): void {
    if (!this.ready) return;
    this.phoneFilter = value;
    if (this.phoneTimer) {
      window.clearTimeout(this.phoneTimer);
    }
    if (value.trim().length < 3) {
      this.phoneSuggestions = [];
      return;
    }
    this.phoneTimer = window.setTimeout(() => {
      this.api.getPhoneSuggestions(value.trim(), 20)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (suggestions) => {
            this.phoneSuggestions = suggestions;
          },
          error: () => {
            this.phoneSuggestions = [];
          }
        });
    }, 300);
  }

  onVoucherFilterChange(value: string): void {
    if (!this.ready) return;
    this.voucherNoFilter = value;
    if (this.voucherTimer) {
      window.clearTimeout(this.voucherTimer);
    }
    if (value.trim().length < 3) {
      this.voucherSuggestions = [];
      return;
    }
    this.voucherTimer = window.setTimeout(() => {
      this.api.getVoucherSuggestions(value.trim(), 20)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (suggestions) => {
            this.voucherSuggestions = suggestions;
          },
          error: () => {
            this.voucherSuggestions = [];
          }
        });
    }, 300);
  }

  onPhoneInputBlur(): void {
    // Delay closing to allow click on suggestion
    setTimeout(() => {
      this.phoneSuggestions = [];
    }, 200);
  }

  onVoucherInputBlur(): void {
    // Delay closing to allow click on suggestion
    setTimeout(() => {
      this.voucherSuggestions = [];
    }, 200);
  }

  selectCustomerSuggestion(suggestion: string): void {
    this.customerFilter = suggestion;
    this.customerSuggestions = [];
  }

  selectPhoneSuggestion(suggestion: string): void {
    this.phoneFilter = suggestion;
    this.phoneSuggestions = [];
  }

  selectVoucherSuggestion(suggestion: string): void {
    this.voucherNoFilter = suggestion;
    this.voucherSuggestions = [];
  }

  applyFilters(): void {
    if (!this.ready) return;
    this.currentPage = 0;
    this.customerSuggestions = [];
    this.phoneSuggestions = [];
    this.voucherSuggestions = [];
    this.loadInvoices();
  }

  clearFilters(): void {
    this.customerFilter = '';
    this.phoneFilter = '';
    this.voucherNoFilter = '';
    this.dateFromFilter = '';
    this.dateToFilter = '';
    this.datePreset = 'none';
    this.receivedAmountMin = '';
    this.receivedAmountMax = '';
    this.currentDueMin = '';
    this.currentDueMax = '';
    this.ageingDaysMin = '';
    this.ageingDaysMax = '';
    this.ageingBucket = 'none';
    this.totalAmountMin = '';
    this.totalAmountMax = '';
    this.yearFilter = '';
    this.monthFilter = '';
    this.quarterFilter = '';
    this.statusFilter = 'all';
    this.sortBy = '';
    this.sortOrder = 'asc';
    this.customerSuggestions = [];
    this.phoneSuggestions = [];
    this.voucherSuggestions = [];
    this.currentPage = 0;
    this.loadInvoices();
  }

  /**
   * Formats a Date object to YYYY-MM-DD string in local timezone
   * This avoids timezone issues when using toISOString() which converts to UTC
   */
  private formatDateLocal(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  applyDatePreset(preset: 'none' | 'today' | 'last7' | 'last30' | 'last90' | 'thisMonth' | 'lastMonth'): void {
    if (!this.ready) return;
    this.datePreset = preset;
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    
    switch (preset) {
      case 'today':
        const todayStr = this.formatDateLocal(today);
        this.dateFromFilter = todayStr;
        this.dateToFilter = todayStr;
        break;
      case 'last7':
        const last7 = new Date(today);
        last7.setDate(last7.getDate() - 7);
        this.dateFromFilter = this.formatDateLocal(last7);
        this.dateToFilter = this.formatDateLocal(today);
        break;
      case 'last30':
        const last30 = new Date(today);
        last30.setDate(last30.getDate() - 30);
        this.dateFromFilter = this.formatDateLocal(last30);
        this.dateToFilter = this.formatDateLocal(today);
        break;
      case 'last90':
        const last90 = new Date(today);
        last90.setDate(last90.getDate() - 90);
        this.dateFromFilter = this.formatDateLocal(last90);
        this.dateToFilter = this.formatDateLocal(today);
        break;
      case 'thisMonth':
        const firstDay = new Date(today.getFullYear(), today.getMonth(), 1);
        this.dateFromFilter = this.formatDateLocal(firstDay);
        this.dateToFilter = this.formatDateLocal(today);
        break;
      case 'lastMonth':
        const lastMonthFirst = new Date(today.getFullYear(), today.getMonth() - 1, 1);
        const lastMonthLast = new Date(today.getFullYear(), today.getMonth(), 0);
        this.dateFromFilter = this.formatDateLocal(lastMonthFirst);
        this.dateToFilter = this.formatDateLocal(lastMonthLast);
        break;
      case 'none':
        this.dateFromFilter = '';
        this.dateToFilter = '';
        break;
    }
    this.applyFilters();
  }

  get activeFilterCount(): number {
    let count = 0;
    if (this.customerFilter) count++;
    if (this.phoneFilter) count++;
    if (this.voucherNoFilter) count++;
    if (this.dateFromFilter || this.dateToFilter) count++;
    if (this.receivedAmountMin || this.receivedAmountMax) count++;
    if (this.currentDueMin || this.currentDueMax) count++;
    if (this.ageingDaysMin || this.ageingDaysMax) count++;
    if (this.ageingBucket !== 'none') count++;
    if (this.totalAmountMin || this.totalAmountMax) count++;
    if (this.yearFilter || this.monthFilter || this.quarterFilter) count++;
    if (this.statusFilter !== 'all') count++;
    if (this.sortBy) count++;
    return count;
  }

  setSorting(column: string): void {
    if (!this.ready) return;
    if (this.sortBy === column) {
      // Toggle sort order if same column
      this.sortOrder = this.sortOrder === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortBy = column;
      this.sortOrder = 'asc';
    }
    this.applyFilters();
  }

  getSortIcon(column: string): string {
    if (this.sortBy !== column) return '↕️';
    return this.sortOrder === 'asc' ? '↑' : '↓';
  }

  getCurrentYear(): number {
    return new Date().getFullYear();
  }

  getYears(): number[] {
    const currentYear = this.getCurrentYear();
    const years: number[] = [];
    for (let i = currentYear; i >= currentYear - 5; i--) {
      years.push(i);
    }
    return years;
  }

  getMonths(): { value: number; label: string }[] {
    return [
      { value: 1, label: 'January' },
      { value: 2, label: 'February' },
      { value: 3, label: 'March' },
      { value: 4, label: 'April' },
      { value: 5, label: 'May' },
      { value: 6, label: 'June' },
      { value: 7, label: 'July' },
      { value: 8, label: 'August' },
      { value: 9, label: 'September' },
      { value: 10, label: 'October' },
      { value: 11, label: 'November' },
      { value: 12, label: 'December' }
    ];
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

  toggleAdvancedFilters(): void {
    this.showAdvancedFilters = !this.showAdvancedFilters;
  }

  onPageChange(page: number): void {
    this.currentPage = page;
    this.loadInvoices();
  }

  onPageSizeChange(size: number): void {
    this.pageSize = size;
    this.currentPage = 0;
    this.loadInvoices();
  }

  get totalPages(): number {
    if (this.totalItems === 0) return 1;
    return Math.ceil(this.totalItems / this.pageSize);
  }

  get pageNumbers(): number[] {
    const pages: number[] = [];
    const total = this.totalPages;
    const current = this.currentPage;
    
    // Show up to 5 page numbers
    let start = Math.max(0, current - 2);
    let end = Math.min(total - 1, current + 2);
    
    // Adjust if we're near the start or end
    if (end - start < 4) {
      if (start === 0) {
        end = Math.min(total - 1, 4);
      } else if (end === total - 1) {
        start = Math.max(0, total - 5);
      }
    }
    
    for (let i = start; i <= end; i++) {
      pages.push(i);
    }
    return pages;
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

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  ngOnDestroy(): void {
    // Clear timers
    if (this.customerTimer) {
      window.clearTimeout(this.customerTimer);
    }
    if (this.phoneTimer) {
      window.clearTimeout(this.phoneTimer);
    }
    if (this.voucherTimer) {
      window.clearTimeout(this.voucherTimer);
    }
    // Complete destroy subject to cleanup subscriptions
    this.destroy$.next();
    this.destroy$.complete();
  }
}


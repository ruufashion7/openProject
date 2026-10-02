import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';
import {
  ApiService,
  CreditPaymentPromise,
  CreditRiskConfig,
  CreditRiskOrderDecision,
  CreditRiskSnapshot,
  CreditRiskSummary,
  CreditRiskCommitteeSummary,
  CreditRiskCollectTodayItem,
  CreditLimitAuditEntry,
  CreditDecisionAuditEntry
} from '../services/api.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { PageStateComponent } from '../shared/page-state/page-state.component';
import { messageFromHttpError } from '../shared/api-error.util';
import { formatPhoneDisplay } from '../shared/phone.util';
import { HttpErrorResponse } from '@angular/common/http';

type QueueTab = 'ALL' | 'READY' | 'BLOCKED' | 'NEED_PAYMENT' | 'NEED_APPROVAL' | 'WATCH' | 'WIND_DOWN';

export const WHATSAPP_OUTREACH_PREFILL_NAMES = 'whatsapp-outreach-prefill-names';
export const WHATSAPP_OUTREACH_PREFILL_MESSAGE = 'whatsapp-outreach-prefill-message';

@Component({
  selector: 'app-credit-risk',
  standalone: true,
  imports: [CommonModule, FormsModule, PageStateComponent, RouterLink],
  templateUrl: './credit-risk.component.html',
  styleUrl: './credit-risk.component.css'
})
export class CreditRiskComponent implements OnInit, OnDestroy {
  formatPhoneDisplay = formatPhoneDisplay;
  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';
  customerQuery = '';
  customerSuggestions: string[] = [];
  showSuggestions = false;

  summary: CreditRiskSummary | null = null;
  orderAmount = '';
  orderReferenceId = '';
  reserveExposure = false;
  decision: CreditRiskOrderDecision | null = null;
  evaluating = false;
  excelUploading = false;

  holdReason = '';
  canManualHold = false;
  canConfigEdit = false;
  canApplyLimit = false;
  showConfig = false;
  config: CreditRiskConfig | null = null;
  savingConfig = false;
  applyingLimit = false;
  paymentTermsInput = '';

  promises: CreditPaymentPromise[] = [];
  cashInvoice = '';
  cashAmount = '';
  cashDays = '10';
  cashNote = '';
  savingCash = false;

  queueTab: QueueTab = 'ALL';
  queue: CreditRiskSnapshot[] = [];
  filteredQueue: CreditRiskSnapshot[] = [];
  queueSearch = '';
  queueSortBy: 'customer' | 'action' | 'risk' | 'overdue' | 'onTime' | 'delay' = 'overdue';
  queueSortDir: 'asc' | 'desc' = 'desc';
  private pendingTakeOrderScroll = false;
  queueLoading = false;
  rebuilding = false;
  customerLoading = false;
  customerLoadError = '';
  showScoreBreakdown = false;
  showTechnicalReasons = false;

  committee: CreditRiskCommitteeSummary | null = null;
  committeeLoading = false;
  collectToday: CreditRiskCollectTodayItem[] = [];
  collectLoading = false;
  limitHistory: CreditLimitAuditEntry[] = [];
  decisionAudits: CreditDecisionAuditEntry[] = [];
  canOverrideDecision = false;
  canWhatsapp = false;
  overrideDecision = 'TAKE_ORDER';
  overrideReason = '';
  overriding = false;

  private destroy$ = new Subject<void>();
  private suggestTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private api: ApiService,
    private permissions: PermissionService,
    private notifications: NotificationService,
    private route: ActivatedRoute,
    private router: Router
  ) {}

  ngOnInit(): void {
    if (!this.permissions.canAccessCreditRisk()) {
      this.status = 'failed';
      this.message = 'You do not have access to Credit Risk.';
      return;
    }
    this.canManualHold = this.permissions.canSetCreditRiskManualHold();
    this.canConfigEdit = this.permissions.canEditCreditRiskConfig();
    this.canApplyLimit = this.permissions.canEditCustomerLimit();
    this.canOverrideDecision = this.permissions.canOverrideCreditRiskDecision();
    this.canWhatsapp = this.permissions.canAccessWhatsappBroadcast();
    this.status = 'idle';
    this.loadQueue();
    this.loadCommittee();
    this.loadCollectToday();
    this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      const customer = (params.get('customer') || params.get('q') || '').trim();
      if (customer) {
        this.customerQuery = customer;
        this.loadSummary(customer);
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    if (this.suggestTimer) {
      clearTimeout(this.suggestTimer);
    }
  }

  onSearchInput(): void {
    if (this.suggestTimer) {
      clearTimeout(this.suggestTimer);
    }
    this.suggestTimer = setTimeout(() => {
      try {
        this.fetchSuggestions();
      } catch {
        this.customerSuggestions = [];
      }
    }, 250);
  }

  onSearchFocus(): void {
    this.showSuggestions = this.customerSuggestions.length > 0;
  }

  clearSearch(): void {
    this.customerQuery = '';
    this.customerSuggestions = [];
    this.showSuggestions = false;
    this.summary = null;
    this.decision = null;
  }

  selectSuggestion(name: string): void {
    this.customerQuery = name;
    this.showSuggestions = false;
    this.loadSummary(name);
  }

  loadSelected(): void {
    const q = this.customerQuery.trim();
    if (q.length < 2) {
      this.notifications.showError('Enter a customer name or phone.');
      return;
    }
    this.loadSummary(q);
  }

  private fetchSuggestions(): void {
    const q = this.customerQuery.trim();
    if (q.length < 3) {
      this.customerSuggestions = [];
      this.showSuggestions = false;
      return;
    }
    this.api.getCustomerSuggestions(q, 30).pipe(takeUntil(this.destroy$)).subscribe({
      next: (list) => {
        this.customerSuggestions = list ?? [];
        this.showSuggestions = this.customerSuggestions.length > 0;
      },
      error: () => {
        this.customerSuggestions = [];
      }
    });
  }

  private loadSummary(query: string): void {
    this.customerLoading = true;
    this.customerLoadError = '';
    this.decision = null;
    const digits = query.replace(/\D/g, '');
    const isPhone = digits.length >= 10 && /^\d[\d\s+\-()]*$/.test(query.trim());
    this.api
      .getCreditRiskSummary(isPhone ? digits : query, isPhone ? digits : undefined)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (s) => {
          this.summary = s;
          this.customerLoading = false;
          this.customerQuery = s.customerName || query;
          this.paymentTermsInput =
            s.paymentTermsDays != null && s.paymentTermsDays > 0 ? String(s.paymentTermsDays) : '';
          this.loadPromises(s.customerKey);
          this.loadCustomerHistory(s.customerKey);
          this.finishTakeOrderFlow(s);
        },
        error: (err: HttpErrorResponse) => {
          this.customerLoading = false;
          this.customerLoadError = messageFromHttpError(err, 'Could not load this customer. Check the name or phone and try again.');
          this.summary = null;
        }
      });
  }

  queueTabLabel(tab: QueueTab): string {
    const labels: Record<QueueTab, string> = {
      ALL: 'All flagged',
      READY: 'Ready for orders',
      BLOCKED: 'Stop orders',
      NEED_PAYMENT: 'Collect payment',
      NEED_APPROVAL: 'Needs approval',
      WATCH: 'Watch list',
      WIND_DOWN: 'Wind down credit'
    };
    return labels[tab];
  }

  actionBucketLabel(bucket: string | null | undefined): string {
    if (!bucket) {
      return '—';
    }
    const map: Record<string, string> = {
      BLOCKED: 'Stop orders',
      NEED_PAYMENT: 'Collect payment',
      NEED_APPROVAL: 'Needs approval',
      WATCH: 'Watch',
      WIND_DOWN: 'Wind down',
      CLEAR: 'OK'
    };
    return map[bucket] ?? bucket.replace(/_/g, ' ').toLowerCase();
  }

  riskCategoryLabel(category: string | null | undefined): string {
    if (!category) {
      return '—';
    }
    const map: Record<string, string> = {
      VERY_RELIABLE: 'Very reliable',
      RELIABLE: 'Reliable',
      WATCH: 'Watch',
      RISKY: 'Risky',
      HIGH_RISK: 'High risk'
    };
    return map[category] ?? category;
  }

  alertLabel(alert: string): string {
    const map: Record<string, string> = {
      COLLECT_CASH_DUE: 'Cash to collect',
      PAYMENT_PROMISE_BROKEN: 'Missed payment promise',
      PAYMENT_DELAY_INCREASING: 'Payments getting slower',
      PAYMENT_FAILURE_INCREASED: 'More bounces / failures'
    };
    return map[alert] ?? alert.replace(/_/g, ' ').toLowerCase();
  }

  dataWarningLabel(warning: string): string {
    const map: Record<string, string> = {
      DUE_DATE_OR_PAYMENT_TERMS_MISSING: 'Some invoices are missing due dates or credit days',
      PAYMENT_STATUS_MISSING: 'Upload Customer Ledger for bounce / payment history'
    };
    return map[warning] ?? warning.replace(/_/g, ' ').toLowerCase();
  }

  orderDecisionLabel(decision: string | null | undefined): string {
    if (!decision) {
      return '—';
    }
    const map: Record<string, string> = {
      TAKE_ORDER: 'Yes — you can take this order',
      TAKE_ORDER_WITH_APPROVAL: 'Only with manager approval',
      TAKE_ORDER_AFTER_PAYMENT: 'Take order after customer pays',
      DO_NOT_TAKE_ORDER: 'Do not take this order',
      MANUAL_HOLD: 'On hold — do not supply'
    };
    return map[decision] ?? decision;
  }

  paymentDecisionLabel(decision: string | null | undefined): string {
    if (!decision) {
      return '—';
    }
    const map: Record<string, string> = {
      CREDIT: 'Normal credit — no advance needed',
      PARTIAL_ADVANCE: 'Ask for partial payment before supply',
      FULL_ADVANCE: 'Full payment before supply'
    };
    return map[decision] ?? decision;
  }

  riskScoreHint(score: number | null | undefined): string {
    if (score == null) {
      return '';
    }
    if (score >= 85) {
      return 'Lower risk — generally safer to extend credit';
    }
    if (score >= 70) {
      return 'Acceptable — monitor overdue and limits';
    }
    if (score >= 55) {
      return 'Caution — tighter limits and follow-up';
    }
    return 'High risk — strict limits and payment before supply';
  }

  evaluateOrder(): void {
    if (!this.summary) {
      return;
    }
    const amount = Number(String(this.orderAmount).replace(/,/g, ''));
    if (!Number.isFinite(amount) || amount < 0) {
      this.notifications.showError('Enter a valid order amount.');
      return;
    }
    this.evaluating = true;
    this.api
      .evaluateCreditRiskOrder({
        customerKey: this.summary.customerKey,
        customer: this.summary.customerName,
        orderAmount: amount,
        referenceId: this.orderReferenceId.trim() || undefined,
        reserveExposure: this.reserveExposure && !!this.orderReferenceId.trim()
      })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (d) => {
          this.decision = d;
          this.evaluating = false;
          this.loadQueue();
        },
        error: (err: HttpErrorResponse) => {
          this.evaluating = false;
          this.notifications.showError(messageFromHttpError(err, 'Order evaluation failed.'));
        }
      });
  }

  onOrderExcelSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.excelUploading = true;
    this.api.evaluateCreditRiskOrderExcel(file, false).pipe(takeUntil(this.destroy$)).subscribe({
      next: (blob) => {
        this.excelUploading = false;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'order-credit-decisions.xlsx';
        a.click();
        URL.revokeObjectURL(url);
        this.notifications.showSuccess('Order decisions downloaded.');
        this.loadQueue();
        input.value = '';
      },
      error: async (err: HttpErrorResponse) => {
        this.excelUploading = false;
        input.value = '';
        let msg = 'Order Excel evaluate failed.';
        try {
          const text = await (err.error as Blob)?.text?.();
          if (text) {
            const parsed = JSON.parse(text) as { error?: string };
            if (parsed.error) {
              msg = parsed.error;
            }
          }
        } catch {
          /* keep default */
        }
        this.notifications.showError(msg);
      }
    });
  }

  applyRecommendedLimit(): void {
    if (!this.summary || !this.canApplyLimit || this.summary.recommendedCreditLimit == null) {
      return;
    }
    this.applyingLimit = true;
    this.api
      .applyCreditRiskRecommendedLimit(this.summary.customerKey, this.summary.customerName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (r) => {
          this.applyingLimit = false;
          this.notifications.showSuccess(`Applied credit limit ₹${Math.round(r.appliedCreditLimit).toLocaleString()}`);
          this.loadSummary(this.summary!.customerName || this.summary!.customerKey);
        },
        error: (err: HttpErrorResponse) => {
          this.applyingLimit = false;
          this.notifications.showError(messageFromHttpError(err, 'Unable to apply recommended limit.'));
        }
      });
  }

  savePaymentTerms(): void {
    if (!this.summary) {
      return;
    }
    const raw = this.paymentTermsInput.trim();
    const days = raw === '' ? null : Number(raw);
    if (raw !== '' && (!Number.isFinite(days) || (days as number) < 0)) {
      this.notifications.showError('Enter valid payment terms days.');
      return;
    }
    this.api
      .setCreditRiskPaymentTerms(this.summary.customerKey, days)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notifications.showSuccess('Payment terms saved.');
          this.loadSummary(this.summary!.customerName || this.summary!.customerKey);
        },
        error: (err: HttpErrorResponse) =>
          this.notifications.showError(messageFromHttpError(err, 'Unable to save payment terms.'))
      });
  }

  private loadPromises(customerKey: string): void {
    this.api.listCreditRiskPromises(customerKey).pipe(takeUntil(this.destroy$)).subscribe({
      next: (list) => (this.promises = list ?? []),
      error: () => (this.promises = [])
    });
  }

  addCashCollect(): void {
    if (!this.summary) {
      return;
    }
    const invoice = this.cashInvoice.trim();
    const amountRaw = this.cashAmount.trim();
    const hasInvoice = invoice.length > 0;
    const hasAmount = amountRaw.length > 0;
    if (!hasInvoice && !hasAmount) {
      this.notifications.showError('Enter an invoice (voucher) number and/or amount to collect.');
      return;
    }
    let promiseAmount: number | undefined;
    if (hasAmount) {
      const amount = Number(amountRaw.replace(/,/g, ''));
      if (!Number.isFinite(amount) || amount <= 0) {
        this.notifications.showError('Enter a valid amount greater than zero.');
        return;
      }
      promiseAmount = amount;
    }
    const days = Number(this.cashDays);
    if (!Number.isFinite(days) || days < 0) {
      this.notifications.showError('Enter days until collection (0 or more).');
      return;
    }
    if (days > 3650) {
      this.notifications.showError('Days until collection cannot exceed 3650.');
      return;
    }
    const note = this.cashNote.trim();
    if (note.length > 200) {
      this.notifications.showError('Note is too long (max 200 characters).');
      return;
    }
    this.savingCash = true;
    this.api
      .createCreditRiskPromise({
        customerKey: this.summary.customerKey,
        customerName: this.summary.customerName,
        voucherNo: hasInvoice ? invoice : undefined,
        daysUntil: days,
        promiseAmount,
        note: note || undefined
      })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.savingCash = false;
          this.cashInvoice = '';
          this.cashAmount = '';
          this.cashDays = '10';
          this.cashNote = '';
          this.notifications.showSuccess('Cash collect reminder added.');
          this.loadPromises(this.summary!.customerKey);
          this.loadSummary(this.summary!.customerName || this.summary!.customerKey);
          this.loadQueue();
        },
        error: (err: HttpErrorResponse) => {
          this.savingCash = false;
          this.notifications.showError(messageFromHttpError(err, 'Unable to add cash collect.'));
        }
      });
  }

  markCashCollected(p: CreditPaymentPromise): void {
    this.api.updateCreditRiskPromiseStatus(p.id, true, false).pipe(takeUntil(this.destroy$)).subscribe({
      next: () => {
        this.loadPromises(this.summary!.customerKey);
        this.loadSummary(this.summary!.customerName || this.summary!.customerKey);
        this.loadQueue();
      },
      error: (err: HttpErrorResponse) =>
        this.notifications.showError(messageFromHttpError(err, 'Unable to update.'))
    });
  }

  markCashBroken(p: CreditPaymentPromise): void {
    this.api.updateCreditRiskPromiseStatus(p.id, false, true).pipe(takeUntil(this.destroy$)).subscribe({
      next: () => {
        this.loadPromises(this.summary!.customerKey);
        this.loadSummary(this.summary!.customerName || this.summary!.customerKey);
        this.loadQueue();
      },
      error: (err: HttpErrorResponse) =>
        this.notifications.showError(messageFromHttpError(err, 'Unable to update.'))
    });
  }

  deleteCashCollect(p: CreditPaymentPromise): void {
    this.api.deleteCreditRiskPromise(p.id).pipe(takeUntil(this.destroy$)).subscribe({
      next: () => {
        this.loadPromises(this.summary!.customerKey);
        this.loadSummary(this.summary!.customerName || this.summary!.customerKey);
        this.loadQueue();
      },
      error: (err: HttpErrorResponse) =>
        this.notifications.showError(messageFromHttpError(err, 'Unable to delete.'))
    });
  }

  setQueueTab(tab: QueueTab): void {
    this.queueTab = tab;
    this.loadQueue();
  }

  queueSectionTitle(): string {
    return this.queueTab === 'READY' ? '1. Grow sales — ready for orders' : '1. Who needs attention';
  }

  queueSectionLead(): string {
    if (this.queueTab === 'READY') {
      return 'Good to sell: rules OK for a typical order (outstanding within limit is fine — overdue / pay-first / stop blocks are excluded).';
    }
    return 'Customers flagged for payment, approval, or blocked orders — sorted by overdue amount.';
  }

  isLikelyOkForOrder(row: CreditRiskSnapshot): boolean {
    if (row.manualHold || row.actionBucket === 'BLOCKED') {
      return false;
    }
    const d = (row.suggestedOrderDecision || '').toUpperCase();
    return d === 'TAKE_ORDER' || d === 'TAKE_ORDER_WITH_APPROVAL';
  }

  takeOrderFromQueue(row: CreditRiskSnapshot, event: Event): void {
    event.stopPropagation();
    const name = row.customerName || row.customerKey;
    const avg = row.averageOrderValue ?? 0;
    this.customerQuery = name;
    this.orderAmount = avg > 0 ? String(Math.round(avg)) : '';
    this.decision = null;
    this.pendingTakeOrderScroll = true;
    this.loadSummary(name);
  }

  startTakeOrderForCustomer(): void {
    if (!this.summary) {
      return;
    }
    const avg = this.summary.averageOrderValueLast90Days || this.summary.averageOrderValue;
    if (avg > 0) {
      this.orderAmount = String(Math.round(avg));
    }
    this.decision = null;
    this.scrollToTakeOrderPanel();
  }

  private finishTakeOrderFlow(s: CreditRiskSummary): void {
    if (!this.pendingTakeOrderScroll) {
      return;
    }
    this.pendingTakeOrderScroll = false;
    if (!this.orderAmount.trim()) {
      const avg = s.averageOrderValueLast90Days || s.averageOrderValue;
      if (avg > 0) {
        this.orderAmount = String(Math.round(avg));
      }
    }
    this.scrollToTakeOrderPanel();
  }

  private scrollToTakeOrderPanel(): void {
    setTimeout(() => {
      document.getElementById('cr-take-order')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 80);
  }

  loadQueue(): void {
    this.queueLoading = true;
    const bucket = this.queueTab === 'READY' ? 'GROW' : this.queueTab;
    this.api.listCreditRiskActionQueue(bucket).pipe(takeUntil(this.destroy$)).subscribe({
      next: (list) => {
        this.queue = list ?? [];
        this.refreshFilteredQueue();
        this.queueLoading = false;
      },
      error: () => {
        this.queue = [];
        this.filteredQueue = [];
        this.queueLoading = false;
      }
    });
  }

  onQueueSearchInput(): void {
    this.refreshFilteredQueue();
  }

  clearQueueSearch(): void {
    this.queueSearch = '';
    this.refreshFilteredQueue();
  }

  private refreshFilteredQueue(): void {
    const q = this.queueSearch.trim().toLowerCase();
    const digits = q.replace(/\D/g, '');

    let list = this.queue.filter((s) => {
      if (!q) {
        return true;
      }
      const name = (s.customerName || '').toLowerCase();
      const phone = (s.phoneNumber || '').replace(/\D/g, '');
      const nameOk = name.includes(q);
      const phoneOk =
        digits.length > 0 &&
        phone.length > 0 &&
        (phone.includes(digits) || digits.includes(phone));
      return nameOk || phoneOk;
    });

    list = [...list].sort((a, b) => this.compareQueueSort(a, b));
    this.filteredQueue = list;
  }

  toggleQueueSort(column: 'customer' | 'action' | 'risk' | 'overdue' | 'onTime' | 'delay'): void {
    if (this.queueSortBy === column) {
      this.queueSortDir = this.queueSortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.queueSortBy = column;
      this.queueSortDir = column === 'customer' || column === 'action' ? 'asc' : 'desc';
    }
    this.refreshFilteredQueue();
  }

  queueSortArrow(column: string): string {
    if (this.queueSortBy !== column) {
      return '⇅';
    }
    return this.queueSortDir === 'asc' ? '↑' : '↓';
  }

  queueSortAria(column: string): 'ascending' | 'descending' | 'none' {
    if (this.queueSortBy !== column) {
      return 'none';
    }
    return this.queueSortDir === 'asc' ? 'ascending' : 'descending';
  }

  private compareQueueSort(a: CreditRiskSnapshot, b: CreditRiskSnapshot): number {
    const dir = this.queueSortDir === 'asc' ? 1 : -1;
    let cmp = 0;
    switch (this.queueSortBy) {
      case 'customer':
        cmp = (a.customerName || '').localeCompare(b.customerName || '', undefined, { sensitivity: 'base' });
        break;
      case 'action':
        cmp = this.actionBucketLabel(a.actionBucket).localeCompare(
          this.actionBucketLabel(b.actionBucket),
          undefined,
          { sensitivity: 'base' }
        );
        break;
      case 'risk':
        cmp = (a.riskScore || 0) - (b.riskScore || 0);
        break;
      case 'overdue':
        cmp = (a.overdueAmount || 0) - (b.overdueAmount || 0);
        break;
      case 'onTime':
        cmp = (a.onTimePaymentPercentage ?? -1) - (b.onTimePaymentPercentage ?? -1);
        break;
      case 'delay':
        cmp = (a.averagePaymentDelayDays ?? -1) - (b.averagePaymentDelayDays ?? -1);
        break;
    }
    return cmp * dir;
  }

  openFromQueue(row: CreditRiskSnapshot): void {
    const name = row.customerName || row.customerKey;
    this.customerQuery = name;
    this.loadSummary(name);
  }

  rebuildSnapshots(): void {
    this.rebuilding = true;
    this.api.rebuildCreditRiskSnapshots().pipe(takeUntil(this.destroy$)).subscribe({
      next: (r) => {
        this.rebuilding = false;
        this.notifications.showSuccess(`Refreshed ${r.rebuilt} customers` + (r.failed ? ` (${r.failed} could not update)` : ''));
        this.loadQueue();
        this.loadCommittee();
        this.loadCollectToday();
      },
      error: (err: HttpErrorResponse) => {
        this.rebuilding = false;
        this.notifications.showError(messageFromHttpError(err, 'Snapshot rebuild failed.'));
      }
    });
  }

  toggleManualHold(): void {
    if (!this.summary || !this.canManualHold) {
      return;
    }
    const next = !this.summary.manualHold;
    if (next && !this.holdReason.trim()) {
      this.notifications.showError('Enter a hold reason.');
      return;
    }
    this.api
      .setCreditRiskManualHold(this.summary.customerKey, next, this.holdReason.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notifications.showSuccess(next ? 'Manual hold applied.' : 'Manual hold cleared.');
          this.loadSummary(this.summary!.customerName || this.summary!.customerKey);
          this.loadQueue();
        },
        error: (err: HttpErrorResponse) => {
          this.notifications.showError(messageFromHttpError(err, 'Unable to update manual hold.'));
        }
      });
  }

  openConfig(): void {
    if (!this.canConfigEdit) {
      return;
    }
    this.showConfig = true;
    this.api.getCreditRiskConfig().pipe(takeUntil(this.destroy$)).subscribe({
      next: (c) => (this.config = c),
      error: (err: HttpErrorResponse) =>
        this.notifications.showError(messageFromHttpError(err, 'Unable to load config.'))
    });
  }

  saveConfig(): void {
    if (!this.config) {
      return;
    }
    this.savingConfig = true;
    this.api.updateCreditRiskConfig(this.config).pipe(takeUntil(this.destroy$)).subscribe({
      next: (c) => {
        this.config = c;
        this.savingConfig = false;
        this.notifications.showSuccess('Config saved.');
      },
      error: (err: HttpErrorResponse) => {
        this.savingConfig = false;
        this.notifications.showError(messageFromHttpError(err, 'Unable to save config.'));
      }
    });
  }

  severityLabel(severity: string | null | undefined): string {
    if (!severity) {
      return '';
    }
    const map: Record<string, string> = {
      INFO: 'Note',
      WARN: 'Caution',
      BLOCK: 'Important'
    };
    return map[severity] ?? severity;
  }

  parameterLabel(parameter: string | null | undefined): string {
    if (!parameter) {
      return '—';
    }
    return parameter
      .replace(/_/g, ' ')
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }

  loadCommittee(): void {
    this.committeeLoading = true;
    this.api.getCreditRiskCommitteeSummary().pipe(takeUntil(this.destroy$)).subscribe({
      next: (c) => {
        this.committee = c;
        this.committeeLoading = false;
      },
      error: () => {
        this.committeeLoading = false;
      }
    });
  }

  loadCollectToday(): void {
    this.collectLoading = true;
    this.api.getCreditRiskCollectToday().pipe(takeUntil(this.destroy$)).subscribe({
      next: (list) => {
        this.collectToday = list ?? [];
        this.collectLoading = false;
      },
      error: () => {
        this.collectToday = [];
        this.collectLoading = false;
      }
    });
  }

  loadLimitHistory(customerKey: string): void {
    this.api.getCreditRiskLimitHistory(customerKey).pipe(takeUntil(this.destroy$)).subscribe({
      next: (h) => (this.limitHistory = h ?? []),
      error: () => (this.limitHistory = [])
    });
  }

  loadDecisionAudits(customerKey: string): void {
    this.api
      .getCreditRiskAudit(customerKey, 20)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (a) => (this.decisionAudits = a ?? []),
        error: () => (this.decisionAudits = [])
      });
  }

  private loadCustomerHistory(customerKey: string): void {
    this.loadLimitHistory(customerKey);
    this.loadDecisionAudits(customerKey);
  }

  openOutstandingDue(customerName: string): void {
    this.router.navigate(['/outstanding-due'], { queryParams: { customer: customerName } });
  }

  openCustomerDetails(customerName: string): void {
    this.router.navigate(['/outstanding'], { queryParams: { customer: customerName } });
  }

  startWhatsappCollectCampaign(): void {
    const names = (this.collectToday.length ? this.collectToday : this.filteredQueue)
      .map((c) => c.customerName)
      .filter((n) => !!n);
    if (!names.length) {
      this.notifications.showError('No customers to message. Refresh the list or pick Collect payment tab.');
      return;
    }
    const template =
      'Hi {{customerName}},\n' +
      'This is a payment reminder. Outstanding due: ₹{{amountDue}}.\n' +
      'Please share your payment date or UTR.\nThank you.';
    sessionStorage.setItem(WHATSAPP_OUTREACH_PREFILL_NAMES, JSON.stringify(names.slice(0, 100)));
    sessionStorage.setItem(WHATSAPP_OUTREACH_PREFILL_MESSAGE, template);
    this.router.navigateByUrl('/whatsapp-outreach');
  }

  exportCollectTodayCsv(): void {
    const rows = this.collectToday;
    if (!rows.length) {
      return;
    }
    const header = 'Customer,Phone,Overdue,Risk,Bucket\n';
    const body = rows
      .map(
        (r) =>
          `"${(r.customerName || '').replace(/"/g, '""')}",` +
          `"${formatPhoneDisplay(r.phoneNumber)}",` +
          `${r.overdueAmount},` +
          `${r.riskScore},` +
          `${r.actionBucket || ''}`
      )
      .join('\n');
    const blob = new Blob([header + body], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `collect-today-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  isFastPathOrder(s: CreditRiskSummary, d: CreditRiskOrderDecision): boolean {
    return (
      s.riskCategory === 'VERY_RELIABLE' &&
      d.orderDecision === 'TAKE_ORDER' &&
      (s.creditUtilization == null || s.creditUtilization < 70)
    );
  }

  submitOverride(): void {
    if (!this.decision?.auditId || !this.overrideReason.trim()) {
      this.notifications.showError('Enter a reason for the override.');
      return;
    }
    this.overriding = true;
    this.api
      .overrideCreditRiskOrder(this.decision.auditId, this.overrideDecision, this.overrideReason.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.overriding = false;
          this.notifications.showSuccess('Decision override saved.');
          this.overrideReason = '';
          if (this.summary) {
            this.loadSummary(this.summary.customerName);
          }
        },
        error: (err: HttpErrorResponse) => {
          this.overriding = false;
          this.notifications.showError(messageFromHttpError(err, 'Override failed.'));
        }
      });
  }

  decisionClass(decision: string | null | undefined): string {
    if (!decision) {
      return '';
    }
    if (decision === 'TAKE_ORDER') {
      return 'ok';
    }
    if (decision === 'TAKE_ORDER_WITH_APPROVAL' || decision === 'TAKE_ORDER_AFTER_PAYMENT') {
      return 'warn';
    }
    return 'bad';
  }
}

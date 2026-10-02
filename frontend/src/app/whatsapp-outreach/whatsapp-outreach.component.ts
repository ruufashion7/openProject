import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  ApiService,
  PaymentDateCustomerCard,
  WhatsappBroadcastBatchResponseDto,
  WhatsappBroadcastBatchSummaryDto,
  WhatsappBroadcastRecipientResponseDto
} from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { formatInrForExcel } from '../shared/format-inr-export';
import { formatPhoneDisplay, normalizePhoneDigits, phoneDigitsMatch } from '../shared/phone.util';

const MAX_TEMPLATE = 3500;
const MAX_BATCH = 500;
/** Per-user draft keys: `${prefix}:user:${userId}` (or `:user:_` when userId missing). */
const MESSAGE_TEMPLATE_STORAGE_PREFIX = 'whatsapp-outreach-message-template-v1';
/** Pre–per-user key; migrated once into the current user’s key when empty. */
const LEGACY_MESSAGE_TEMPLATE_STORAGE_KEY = 'whatsapp-outreach-message-template-v1';
const PERSIST_TEMPLATE_DEBOUNCE_MS = 400;
/** One browser tab for all Open WhatsApp clicks (navigate in place instead of many tabs). */
const WHATSAPP_CHAT_WINDOW_NAME = 'openproject-whatsapp-chat';

function normalizeCustomerKey(displayName: string): string {
  return (displayName || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_');
}

/** Prefer web.whatsapp.com/send so an existing WhatsApp Web tab can be reused. */
function toWhatsappWebSendUrl(waMeUrl: string): string {
  try {
    const parsed = new URL(waMeUrl);
    if (parsed.hostname !== 'wa.me' && parsed.hostname !== 'api.whatsapp.com') {
      return waMeUrl;
    }
    const pathPhone = parsed.pathname.replace(/\D/g, '');
    const text = parsed.searchParams.get('text');
    const web = new URL('https://web.whatsapp.com/send');
    if (pathPhone) {
      web.searchParams.set('phone', pathPhone);
    }
    if (text) {
      web.searchParams.set('text', text);
    }
    return web.toString();
  } catch {
    return waMeUrl;
  }
}

@Component({
  selector: 'app-whatsapp-outreach',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './whatsapp-outreach.component.html',
  styleUrl: './whatsapp-outreach.component.css'
})
export class WhatsappOutreachComponent implements OnInit, OnDestroy {
  formatPhoneDisplay = formatPhoneDisplay;

  readonly maxTemplate = MAX_TEMPLATE;

  /**
   * Built-ins + Outstanding Due fields. Use {{tokenName}} in the message; insert via chips below the textarea.
   * Backend only replaces tokens matching [a-zA-Z][a-zA-Z0-9_]* inside {{ }}.
   */
  readonly placeholderChips: { token: string; label: string }[] = [
    { token: '{{customerName}}', label: 'Customer name' },
    { token: '{{phone}}', label: 'Phone (digits)' },
    { token: '{{nextPaymentDate}}', label: 'Next due date' },
    { token: '{{amountDue}}', label: 'Amount due (₹)' },
    { token: '{{totalAmount}}', label: 'Total amount (₹)' },
    { token: '{{place}}', label: 'Place' },
    { token: '{{address}}', label: 'Address' },
    { token: '{{lastOrderDate}}', label: 'Last invoice date (sales)' },
    { token: '{{lastInvoiceDate}}', label: 'Last invoice date (alias)' },
    { token: '{{withinAmount}}', label: '1-45 days due (₹)' },
    { token: '{{midAmount}}', label: '46-85 days due (₹)' },
    { token: '{{beyondAmount}}', label: '85+ days due (₹)' },
    { token: '{{customerCategory}}', label: 'Category' },
    { token: '{{whatsAppStatus}}', label: 'WhatsApp status' },
    { token: '{{needsFollowUp}}', label: 'Needs follow-up' }
  ];

  @ViewChild('messageTextarea') private messageTextareaRef?: ElementRef<HTMLTextAreaElement>;

  /** Shown in the top banner (short). */
  get placeholderHint(): string {
    return '{{customerName}}, {{phone}}, {{nextPaymentDate}}, {{amountDue}}, …';
  }

  messageTemplate = '';
  cards: PaymentDateCustomerCard[] = [];
  cardsLoadError = '';
  loadingCards = false;

  selectedCustomers = new Set<string>();
  customerSearch = '';

  /** Filtered recipient rows (updated on input — same matching rules as Outstanding Due search). */
  recipientPickListFiltered: PaymentDateCustomerCard[] = [];
  recipientSearchSuggestions: { name: string; phone: string }[] = [];
  showRecipientSuggestions = false;

  batch: WhatsappBroadcastBatchResponseDto | null = null;
  loadingBatch = false;
  saving = false;

  batchSummaries: WhatsappBroadcastBatchSummaryDto[] = [];
  loadingSummaries = false;

  showConfirm = false;
  statusFilter: 'ALL' | 'NOT_SENT' | 'IN_PROGRESS' | 'SENT' | 'FAILED' = 'ALL';

  messageSectionExpanded = true;
  recipientsSectionExpanded = false;
  recentBatchesSectionExpanded = false;

  private persistTemplateTimer: ReturnType<typeof setTimeout> | null = null;
  private restoringLatestBatchTemplate = false;

  constructor(
    private api: ApiService,
    private auth: AuthService,
    readonly permission: PermissionService,
    private router: Router,
    private route: ActivatedRoute,
    private notifications: NotificationService
  ) {}

  ngOnInit(): void {
    if (!this.permission.canAccessWhatsappBroadcast()) {
      this.notifications.showPermissionError();
      this.router.navigateByUrl('/welcome');
      return;
    }
    this.route.queryParamMap.subscribe((params) => {
      const bid = params.get('batch');
      if (bid) {
        this.loadBatch(bid);
      } else {
        this.batch = null;
        this.enterComposerMode();
      }
    });
  }

  ngOnDestroy(): void {
    this.flushPersistMessageTemplate();
  }

  onMessageTemplateDraftChange(): void {
    this.schedulePersistMessageTemplate();
  }

  insertPlaceholder(token: string): void {
    const el = this.messageTextareaRef?.nativeElement;
    const v = this.messageTemplate;
    if (!el) {
      this.messageTemplate = v + token;
      this.schedulePersistMessageTemplate();
      return;
    }
    const start = el.selectionStart ?? v.length;
    const end = el.selectionEnd ?? v.length;
    this.messageTemplate = v.slice(0, start) + token + v.slice(end);
    this.schedulePersistMessageTemplate();
    setTimeout(() => {
      el.focus();
      const pos = start + token.length;
      el.setSelectionRange(pos, pos);
    });
  }

  private buildPlaceholdersFromCard(card: PaymentDateCustomerCard): Record<string, string> {
    const p: Record<string, string> = {};
    const nd = card.nextPaymentDate;
    if (nd != null && String(nd).trim() !== '') {
      p['nextPaymentDate'] = String(nd).trim();
    }
    if (card.totalAmount != null && Number.isFinite(Number(card.totalAmount))) {
      const due = formatInrForExcel(Number(card.totalAmount));
      p['totalAmount'] = due;
      p['amountDue'] = due;
    }
    const place = card.place;
    if (place != null && String(place).trim() !== '') {
      p['place'] = String(place).trim();
    }
    const addr = card.address;
    if (addr != null && String(addr).trim() !== '') {
      p['address'] = String(addr).trim();
    }
    const lod = card.lastOrderDate;
    if (lod != null && String(lod).trim() !== '') {
      const trimmed = String(lod).trim();
      p['lastOrderDate'] = trimmed;
      p['lastInvoiceDate'] = trimmed;
    }
    if (card.withinAmount != null && Number.isFinite(Number(card.withinAmount))) {
      p['withinAmount'] = formatInrForExcel(Number(card.withinAmount));
    }
    if (card.midAmount != null && Number.isFinite(Number(card.midAmount))) {
      p['midAmount'] = formatInrForExcel(Number(card.midAmount));
    }
    if (card.beyondAmount != null && Number.isFinite(Number(card.beyondAmount))) {
      p['beyondAmount'] = formatInrForExcel(Number(card.beyondAmount));
    }
    const cat = card.customerCategory;
    if (cat != null && String(cat).trim() !== '') {
      p['customerCategory'] = String(cat).trim();
    }
    const ws = card.whatsAppStatus;
    if (ws != null && String(ws).trim() !== '') {
      p['whatsAppStatus'] = String(ws).trim();
    }
    if (card.needsFollowUp === true) {
      p['needsFollowUp'] = 'Yes';
    } else if (card.needsFollowUp === false) {
      p['needsFollowUp'] = 'No';
    }
    return p;
  }

  private messageTemplateStorageKey(): string {
    const uid = this.auth.getUserId();
    const suffix = uid && uid.length > 0 ? uid : '_';
    return `${MESSAGE_TEMPLATE_STORAGE_PREFIX}:user:${suffix}`;
  }

  /** Composer step: restore draft, then last batch message if still empty. */
  private enterComposerMode(): void {
    const hadDraft = this.restoreMessageTemplateFromStorage();
    if (!hadDraft && !this.messageTemplate.trim()) {
      this.restoreMessageTemplateFromLatestBatch();
    }
    this.loadCards();
    this.loadBatchSummaries();
  }

  /** @returns true if a non-empty draft was loaded */
  private restoreMessageTemplateFromStorage(): boolean {
    if (typeof localStorage === 'undefined') {
      return false;
    }
    try {
      const primary = this.messageTemplateStorageKey();
      const keys = [
        primary,
        `${MESSAGE_TEMPLATE_STORAGE_PREFIX}:user:_`,
        LEGACY_MESSAGE_TEMPLATE_STORAGE_KEY
      ];
      let raw: string | null = null;
      let hitKey: string | null = null;
      for (const k of keys) {
        const v = localStorage.getItem(k);
        if (v != null && v.length > 0) {
          raw = v;
          hitKey = k;
          break;
        }
      }
      if (raw == null) {
        return false;
      }
      if (hitKey !== primary && hitKey != null) {
        localStorage.setItem(primary, raw);
        if (hitKey === LEGACY_MESSAGE_TEMPLATE_STORAGE_KEY) {
          localStorage.removeItem(LEGACY_MESSAGE_TEMPLATE_STORAGE_KEY);
        }
      }
      this.messageTemplate = raw.length > MAX_TEMPLATE ? raw.slice(0, MAX_TEMPLATE) : raw;
      return this.messageTemplate.trim().length > 0;
    } catch {
      return false;
    }
  }

  private restoreMessageTemplateFromLatestBatch(): void {
    if (this.restoringLatestBatchTemplate || !this.permission.canAccessWhatsappBroadcast()) {
      return;
    }
    this.restoringLatestBatchTemplate = true;
    this.api.listWhatsappBroadcasts().subscribe({
      next: (rows) => {
        const id = rows?.[0]?.id;
        if (!id || this.messageTemplate.trim()) {
          this.restoringLatestBatchTemplate = false;
          return;
        }
        this.api.getWhatsappBroadcast(id).subscribe({
          next: (b) => {
            this.restoringLatestBatchTemplate = false;
            const t = (b?.messageTemplate || '').trim();
            if (!t || this.messageTemplate.trim()) {
              return;
            }
            this.messageTemplate = t.length > MAX_TEMPLATE ? t.slice(0, MAX_TEMPLATE) : t;
            this.persistMessageTemplateToStorage();
          },
          error: () => {
            this.restoringLatestBatchTemplate = false;
          }
        });
      },
      error: () => {
        this.restoringLatestBatchTemplate = false;
      }
    });
  }

  private persistMessageTemplateToStorage(): void {
    if (typeof localStorage === 'undefined') {
      return;
    }
    try {
      localStorage.setItem(this.messageTemplateStorageKey(), this.messageTemplate);
    } catch {
      /* quota / private mode */
    }
  }

  private schedulePersistMessageTemplate(): void {
    if (this.persistTemplateTimer != null) {
      clearTimeout(this.persistTemplateTimer);
    }
    this.persistTemplateTimer = setTimeout(() => {
      this.persistTemplateTimer = null;
      this.persistMessageTemplateToStorage();
    }, PERSIST_TEMPLATE_DEBOUNCE_MS);
  }

  private flushPersistMessageTemplate(): void {
    if (this.persistTemplateTimer != null) {
      clearTimeout(this.persistTemplateTimer);
      this.persistTemplateTimer = null;
    }
    this.persistMessageTemplateToStorage();
  }

  loadBatchSummaries(): void {
    if (!this.permission.canAccessWhatsappBroadcast()) {
      return;
    }
    this.loadingSummaries = true;
    this.api.listWhatsappBroadcasts().subscribe({
      next: (rows) => {
        this.batchSummaries = rows ?? [];
        this.loadingSummaries = false;
      },
      error: () => {
        this.batchSummaries = [];
        this.loadingSummaries = false;
      }
    });
  }

  loadCards(): void {
    if (!this.permission.canAccessOutstandingPage()) {
      this.cardsLoadError =
        'Outstanding Due access is required to load customers here. Ask an admin to enable that permission for your account.';
      this.recipientPickListFiltered = [];
      this.recipientSearchSuggestions = [];
      this.showRecipientSuggestions = false;
      return;
    }
    this.loadingCards = true;
    this.cardsLoadError = '';
    this.api.getOutstandingDue().subscribe({
      next: (c) => {
        this.cards = (c || []).filter(
          (x) => (x.customer || '').toLowerCase().trim() !== 'total'
        );
        this.loadingCards = false;
        this.applyCreditRiskPrefill();
        this.refreshRecipientPickList();
      },
      error: () => {
        this.loadingCards = false;
        this.cardsLoadError =
          'Could not load customers. Check your connection and try again, or open Outstanding Due from the menu.';
        this.recipientPickListFiltered = [];
        this.recipientSearchSuggestions = [];
        this.showRecipientSuggestions = false;
      }
    });
  }

  loadBatch(id: string): void {
    this.loadingBatch = true;
    this.api.getWhatsappBroadcast(id).subscribe({
      next: (b) => {
        this.batch = b;
        this.loadingBatch = false;
      },
      error: () => {
        this.loadingBatch = false;
        this.notifications.showError('Batch not found or access denied.');
        this.router.navigate([], { queryParams: {}, replaceUrl: true });
      }
    });
  }

  get hasEligibleCustomers(): boolean {
    return this.cards.some(
      (c) => normalizePhoneDigits(c.phoneNumber).length >= 10
    );
  }

  summaryStatsLine(s: WhatsappBroadcastBatchSummaryDto): string {
    const parts: string[] = [];
    if (s.sentCount > 0) {
      parts.push(`${s.sentCount} sent`);
    }
    if (s.failedCount > 0) {
      parts.push(`${s.failedCount} failed`);
    }
    const pending = s.notSentCount + s.inProgressCount;
    if (pending > 0) {
      parts.push(`${pending} pending`);
    }
    if (parts.length === 0) {
      return s.recipientCount === 0 ? 'No recipients' : '—';
    }
    return parts.join(' · ');
  }

  private eligibleCardsWithPhone(): PaymentDateCustomerCard[] {
    return this.cards.filter(
      (c) => normalizePhoneDigits(c.phoneNumber).length >= 10
    );
  }

  /** Customers with a valid phone (10+ digits), ignoring search. */
  get eligiblePickList(): PaymentDateCustomerCard[] {
    return this.eligibleCardsWithPhone();
  }

  /** Same name/phone/place rules as Outstanding Due {@code filterCardsByState} search. */
  private matchesRecipientSearchQuery(card: PaymentDateCustomerCard, rawQuery: string): boolean {
    const q = rawQuery.toLowerCase().trim();
    if (!q) {
      return true;
    }
    const normalizedQuery = q.replace(/\D/g, '');
    const name = (card.customer || '').toLowerCase();
    const place = (card.place || '').toLowerCase();
    const nameMatch = name.includes(q);
    const placeMatch = !!place && place.includes(q);
    const phoneMatch = normalizedQuery ? phoneDigitsMatch(card.phoneNumber, normalizedQuery) : false;
    return nameMatch || placeMatch || phoneMatch;
  }

  refreshRecipientPickList(): void {
    const eligible = this.eligibleCardsWithPhone();
    const raw = this.customerSearch.trim();
    if (!raw) {
      this.recipientPickListFiltered = eligible;
      return;
    }
    this.recipientPickListFiltered = eligible.filter((c) => this.matchesRecipientSearchQuery(c, raw));
  }

  updateRecipientSearchSuggestions(): void {
    const raw = this.customerSearch.trim();
    if (!raw) {
      this.recipientSearchSuggestions = [];
      this.showRecipientSuggestions = false;
      return;
    }
    this.recipientSearchSuggestions = this.eligibleCardsWithPhone()
      .filter((c) => this.matchesRecipientSearchQuery(c, raw))
      .slice(0, 8)
      .map((card) => ({
        name: card.customer || '',
        phone: formatPhoneDisplay(card.phoneNumber)
      }));
    this.showRecipientSuggestions = this.recipientSearchSuggestions.length > 0;
  }

  onRecipientSearchInput(): void {
    this.refreshRecipientPickList();
    this.updateRecipientSearchSuggestions();
  }

  onRecipientSearchFocus(): void {
    this.updateRecipientSearchSuggestions();
  }

  selectRecipientSuggestion(s: { name: string; phone: string }): void {
    this.customerSearch = s.name || s.phone;
    this.showRecipientSuggestions = false;
    this.recipientSearchSuggestions = [];
    this.refreshRecipientPickList();
  }

  get allFilteredSelected(): boolean {
    const list = this.recipientPickListFiltered;
    if (list.length === 0) {
      return false;
    }
    return list.every((c) => !!c.customer && this.selectedCustomers.has(c.customer));
  }

  toggleCustomer(name: string): void {
    if (this.selectedCustomers.has(name)) {
      this.selectedCustomers.delete(name);
    } else {
      this.selectedCustomers.add(name);
    }
    this.selectedCustomers = new Set(this.selectedCustomers);
  }

  isSelected(name: string): boolean {
    return this.selectedCustomers.has(name);
  }

  selectAllVisible(): void {
    for (const c of this.recipientPickListFiltered) {
      if (c.customer) {
        this.selectedCustomers.add(c.customer);
      }
    }
    this.selectedCustomers = new Set(this.selectedCustomers);
  }

  /** Every eligible customer (ignores search filter). */
  selectAllEligible(): void {
    for (const c of this.eligiblePickList) {
      if (c.customer) {
        this.selectedCustomers.add(c.customer);
      }
    }
    this.selectedCustomers = new Set(this.selectedCustomers);
  }

  toggleSelectAllFiltered(): void {
    if (this.allFilteredSelected) {
      for (const c of this.recipientPickListFiltered) {
        if (c.customer) {
          this.selectedCustomers.delete(c.customer);
        }
      }
    } else {
      this.selectAllVisible();
    }
    this.selectedCustomers = new Set(this.selectedCustomers);
  }

  clearSelection(): void {
    this.selectedCustomers.clear();
    this.selectedCustomers = new Set();
  }

  clearRecipientSearch(): void {
    this.customerSearch = '';
    this.recipientSearchSuggestions = [];
    this.showRecipientSuggestions = false;
    this.refreshRecipientPickList();
  }

  openConfirm(): void {
    this.flushPersistMessageTemplate();
    const t = this.messageTemplate.trim();
    if (!t) {
      this.notifications.showError('Enter a message to send.');
      return;
    }
    if (t.length > MAX_TEMPLATE) {
      this.notifications.showError(`Message is too long (max ${MAX_TEMPLATE} characters).`);
      return;
    }
    if (this.selectedCustomers.size === 0) {
      this.notifications.showError('Select at least one customer with a valid phone number.');
      return;
    }
    if (this.selectedCustomers.size > MAX_BATCH) {
      this.notifications.showError(`Maximum ${MAX_BATCH} recipients per batch.`);
      return;
    }
    this.showConfirm = true;
  }

  cancelConfirm(): void {
    this.showConfirm = false;
  }

  submitBatch(): void {
    this.flushPersistMessageTemplate();
    const t = this.messageTemplate.trim();
    const recipients = Array.from(this.selectedCustomers)
      .map((name) => {
        const card = this.cards.find((c) => c.customer === name);
        if (!card || !card.phoneNumber) {
          return null;
        }
        const placeholders = this.buildPlaceholdersFromCard(card);
        return {
          customerKey: normalizeCustomerKey(card.customer || ''),
          displayName: card.customer || '',
          phoneNumber: card.phoneNumber,
          placeholders: Object.keys(placeholders).length > 0 ? placeholders : undefined
        };
      })
      .filter((r): r is NonNullable<typeof r> => r !== null);

    if (recipients.length === 0) {
      this.notifications.showError('No valid recipients.');
      return;
    }

    this.saving = true;
    this.api.createWhatsappBroadcast({ messageTemplate: t, recipients }).subscribe({
      next: (b) => {
        this.saving = false;
        this.showConfirm = false;
        this.batch = b;
        this.router.navigate([], {
          queryParams: { batch: b.id },
          replaceUrl: true
        });
        this.flushPersistMessageTemplate();
        this.notifications.showSuccess('Batch created. Open WhatsApp for each customer and mark status when done.', 5000);
        this.loadBatchSummaries();
      },
      error: (err) => {
        this.saving = false;
        const msg = err.error?.error || err.error?.message || 'Failed to create batch.';
        this.notifications.showError(typeof msg === 'string' ? msg : 'Failed to create batch.');
      }
    });
  }

  get filteredRecipients(): WhatsappBroadcastRecipientResponseDto[] {
    if (!this.batch) {
      return [];
    }
    const r = this.batch.recipients || [];
    if (this.statusFilter === 'ALL') {
      return r;
    }
    return r.filter((x) => x.status === this.statusFilter);
  }

  statusLabel(s: string): string {
    switch (s) {
      case 'NOT_SENT':
        return 'Not sent';
      case 'IN_PROGRESS':
        return 'In progress';
      case 'SENT':
        return 'Sent';
      case 'FAILED':
        return 'Failed';
      default:
        return s;
    }
  }

  statusClass(s: string): string {
    switch (s) {
      case 'NOT_SENT':
        return 'st-muted';
      case 'IN_PROGRESS':
        return 'st-warn';
      case 'SENT':
        return 'st-ok';
      case 'FAILED':
        return 'st-bad';
      default:
        return 'st-muted';
    }
  }

  openWhatsapp(rec: WhatsappBroadcastRecipientResponseDto): void {
    if (!this.batch) {
      return;
    }
    this.api.getWhatsappWaLink(this.batch.id, rec.id, true).subscribe({
      next: (res) => {
        const url = toWhatsappWebSendUrl(res.url);
        const chat = window.open(url, WHATSAPP_CHAT_WINDOW_NAME);
        if (!chat) {
          window.location.assign(url);
        } else {
          chat.focus();
        }
        this.loadBatch(this.batch!.id);
      },
      error: () => this.notifications.showError('Could not open WhatsApp link.')
    });
  }

  copyText(text: string): void {
    navigator.clipboard.writeText(text).then(
      () => this.notifications.showSuccess('Copied to clipboard.', 2000),
      () => this.notifications.showError('Copy failed.')
    );
  }

  markStatus(rec: WhatsappBroadcastRecipientResponseDto, status: string): void {
    if (!this.batch) {
      return;
    }
    this.api.patchWhatsappRecipient(this.batch.id, rec.id, { status }).subscribe({
      next: () => this.loadBatch(this.batch!.id),
      error: () => this.notifications.showError('Could not update status.')
    });
  }

  newBatch(): void {
    this.flushPersistMessageTemplate();
    this.batch = null;
    this.clearSelection();
    this.router.navigate([], { queryParams: {}, replaceUrl: true });
    this.enterComposerMode();
  }

  channelLabel(mode: string): string {
    return mode === 'CLOUD_API' ? 'Cloud API (when enabled)' : 'WhatsApp link (wa.me)';
  }

  private applyCreditRiskPrefill(): void {
    const raw = sessionStorage.getItem('whatsapp-outreach-prefill-names');
    if (!raw) {
      return;
    }
    sessionStorage.removeItem('whatsapp-outreach-prefill-names');
    const msg = sessionStorage.getItem('whatsapp-outreach-prefill-message');
    if (msg) {
      sessionStorage.removeItem('whatsapp-outreach-prefill-message');
      this.messageTemplate = msg;
      this.schedulePersistMessageTemplate();
    }
    let names: string[] = [];
    try {
      names = JSON.parse(raw) as string[];
    } catch {
      return;
    }
    const nameSet = new Set(names.map((n) => (n || '').trim().toLowerCase()));
    for (const card of this.cards) {
      const key = normalizeCustomerKey(card.customer);
      if (nameSet.has((card.customer || '').trim().toLowerCase())) {
        this.selectedCustomers.add(key);
      }
    }
    this.messageSectionExpanded = true;
    this.recipientsSectionExpanded = true;
    this.notifications.showSuccess(`Pre-selected ${this.selectedCustomers.size} customer(s) from Credit Risk.`);
  }
}

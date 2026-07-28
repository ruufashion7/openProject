import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { SecurityService } from '../security/security.service';

export interface HealthStatus {
  status: string;
  timestamp: string;
}

export interface DashboardFileStatus {
  present: boolean;
  filename: string | null;
  uploadedAt: string | null;
  rowCount: number;
}

export interface DashboardActivityItem {
  action: string;
  type: string;
  filename: string;
  uploadedAt: string;
}

export interface DashboardSystemInfo {
  applicationName: string;
  version: string;
  activeProfiles: string;
  rateLimitBackend: string;
  redisRateLimitActive: boolean;
}

export interface DashboardUploadJobInfo {
  busy: boolean;
  state: string;
  phase: string | null;
  message: string;
  startedBy: string | null;
  startedAt: string | null;
  completedAt: string | null;
  filenames: string[];
}

export interface DashboardCustomerStats {
  totalRecords: number;
  activeInUpload: number;
  withPhone: number;
  withLocation: number;
}

export interface DashboardWhatsappStats {
  batches: number;
  recipients: number;
  notSent: number;
  inProgress: number;
  sent: number;
  failed: number;
  latestBatchAt: string | null;
}

export interface DashboardUserRow {
  displayName: string;
  username: string;
  admin: boolean;
  active: boolean;
  updatedAt: string;
}

export interface DashboardSummary {
  generatedAt: string;
  analyticsReady: boolean;
  system: DashboardSystemInfo;
  uploadJob: DashboardUploadJobInfo;
  customers: DashboardCustomerStats;
  whatsapp: DashboardWhatsappStats;
  activeUsers: number;
  inactiveUsers: number;
  adminUsers: number;
  activeSessions: number;
  rateListEntries: number;
  uploadAuditEntries: number;
  detailedFile: DashboardFileStatus;
  receivableFile: DashboardFileStatus;
  users: DashboardUserRow[];
  recentUploadActivity: DashboardActivityItem[];
  alerts: string[];
}

export interface UploadResponse {
  status: 'success' | 'failed';
  message: string;
  files: Array<{ id: string; filename: string }>;
}

/** HTTP 409 when another upload is running — includes active job id for polling/cancel. */
export interface UploadConflictResponse {
  status: 'failed';
  message: string;
  currentJobId: string | null;
}

/** 202 response from POST /upload — processing continues in the background. */
export interface UploadJobAcceptedResponse {
  jobId: string;
  message: string;
}

/** GET /upload/jobs/{jobId} — async upload progress and outcome. */
export interface UploadJobStatusResponse {
  jobId: string;
  state: 'processing' | 'success' | 'failed' | 'cancelled';
  message: string;
  files: Array<{ id: string; filename: string }>;
  phase: string | null;
  cancellable: boolean;
  startedByUserId: string;
  startedByDisplayName: string;
  startedAt: string;
}

/** Last finished async upload (server-side, all users see the same). */
export interface UploadLastOutcomeResponse {
  jobId: string;
  state: string;
  message: string;
  files: Array<{ id: string; filename: string }>;
  completedAt: string;
  startedByUserId: string;
  startedByDisplayName: string;
}

export interface UploadCurrentJobResponse {
  jobId: string;
  state: string;
  message: string;
  phase: string | null;
  cancellable: boolean;
  startedAt: string;
  startedByUserId: string;
  startedByDisplayName: string;
}

/** GET /api/upload/state — global async upload visibility. */
export interface UploadAsyncStateResponse {
  busy: boolean;
  currentJob: UploadCurrentJobResponse | null;
  lastOutcome: UploadLastOutcomeResponse | null;
}

export interface UploadCancelResponse {
  status: 'accepted' | 'failed';
  message: string;
}

export interface UploadEntry {
  id: string;
  type: 'detailed' | 'receivable';
  originalFilename: string;
  uploadedAt: string;
}

export interface UploadAuditEntry {
  id: string;
  action: 'ADDED' | 'DELETED' | 'STOPPED' | 'CANCELLED' | 'FAILED';
  type: 'detailed' | 'receivable';
  originalFilename: string;
  uploadedAt: string;
  message?: string;
}

export interface UploadPurgeResponse {
  detailedDeleted: number;
  receivableDeleted: number;
}

export interface UploadStatusResponse {
  hasDetailed: boolean;
  hasReceivable: boolean;
  ready: boolean;
}

export interface CustomerSummaryResponse {
  customer: string;
  found: boolean;
  phoneNumber: string | null;
  totalAmount: number;
  within45Days: boolean;
  withinAmount: number;
  midAmount: number;
  beyondAmount: number;
  unknownAmount: number;
  nextPaymentDate?: string | null;
  whatsAppStatus?: string | null;
  customerCategory?: string | null;
  needsFollowUp?: boolean | null;
  address?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  place?: string | null;
}

export interface CustomerLocation {
  customerName: string;
  phoneNumber: string | null;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
}

export interface CustomerLedgerEntry {
  invoiceDate: string | null;
  voucherNo: string | null;
  receivedAmount: number | string;
  currentDue: number | string;
  ageingDays?: number | null;
}

export interface PaymentDateCustomerCard {
  customer: string;
  totalAmount: number;
  nextPaymentDate?: string | null;
  phoneNumber?: string | null;
  whatsAppStatus?: string | null;
  customerCategory?: string | null;
  lastOrderDate?: string | null;
  needsFollowUp?: boolean | null;
  address?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  place?: string | null;
  /** Receivable ageing bucket amounts — matches Customer Details summary. */
  withinAmount?: number;
  midAmount?: number;
  beyondAmount?: number;
  /** True when customer is retained (shown even with ₹0). */
  retained?: boolean | null;
}

export interface ExcludedCustomerView {
  customerKey: string;
  customerName: string;
  excludedAt?: string | null;
  excludedBy?: string | null;
}

export interface RetainedCustomerView {
  customerKey: string;
  customerName: string;
  retainedAt?: string | null;
  retainedBy?: string | null;
}

export interface WhatsappBroadcastRecipientInputDto {
  customerKey: string;
  displayName: string;
  phoneNumber: string;
  /** Optional {{token}} values merged per recipient (Outstanding Due fields, etc.). */
  placeholders?: Record<string, string> | null;
}

export interface WhatsappBroadcastCreateRequestDto {
  messageTemplate: string;
  recipients: WhatsappBroadcastRecipientInputDto[];
}

export interface WhatsappBroadcastRecipientResponseDto {
  id: string;
  customerKey: string;
  displayName: string;
  phoneDigits: string;
  renderedMessage: string;
  status: string;
  failureReason: string | null;
  openedAt: string | null;
  sentAt: string | null;
}

export interface WhatsappBroadcastBatchResponseDto {
  id: string;
  messageTemplate: string;
  channelMode: string;
  createdAt: string | null;
  recipients: WhatsappBroadcastRecipientResponseDto[];
}

export interface WhatsappBroadcastBatchSummaryDto {
  id: string;
  createdAt: string | null;
  channelMode: string;
  messagePreview: string;
  recipientCount: number;
  notSentCount: number;
  inProgressCount: number;
  sentCount: number;
  failedCount: number;
}

export interface WhatsappWaLinkResponseDto {
  url: string;
}

export interface SalesInvoiceEntry {
  invoiceDate: string | null;
  voucherNo: string | null;
  customer: string;
  customerPhone: string;
  receivedAmount: number;
  currentDue: number;
  ageingDays?: number | null;
}

export interface SalesInvoicePageResponse {
  content: SalesInvoiceEntry[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

export interface SessionListItem {
  userId: string;
  tokenPreview: string;
  displayName: string;
  expiresAt: string;
  isAdmin: boolean;
  isExpired: boolean;
}

export interface UpdateSessionRequest {
  userId: string;
  expiresAt: string;
}

export interface RateListEntry {
  id?: string;
  date: 'old' | 'new';
  type: 'landing' | 'resale';
  productName: string;
  size: '80-90' | '95-100';
  rate: number;
  srNo?: number; // Serial number for product ordering
  createdAt?: string;
}

export interface CustomerNote {
  id: string;
  customerName: string | null;
  phoneNumber: string | null;
  note: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  updatedBy: string;
}

export interface CustomerNoteSearchRequest {
  customerName?: string | null;
  phoneNumber?: string | null;
}

export interface CreateCustomerNoteRequest {
  customerName?: string | null;
  phoneNumber?: string | null;
  note: string;
}

export interface UpdateCustomerNoteRequest {
  noteId: string;
  note: string;
}

export interface DeleteCustomerNoteRequest {
  noteId: string;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly baseUrl = '/api';

  constructor(private http: HttpClient, private auth: AuthService) {}

  getHealth(): Observable<HealthStatus> {
    return this.http.get<HealthStatus>(`${this.baseUrl}/health`);
  }

  getDashboard(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${this.baseUrl}/dashboard`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  /**
   * Starts an async upload. Returns 202 with {@link UploadJobAcceptedResponse}; poll {@link getUploadJobStatus}.
   * 400/409 errors return {@link UploadResponse} in the error body.
   */
  uploadFiles(file1: File, file2: File): Observable<UploadJobAcceptedResponse> {
    const formData = new FormData();
    formData.append('file1', file1);
    formData.append('file2', file2);
    return this.http.post<UploadJobAcceptedResponse>(`${this.baseUrl}/upload`, formData, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getUploadJobStatus(jobId: string): Observable<UploadJobStatusResponse> {
    if (!SecurityService.validateId(jobId)) {
      throw new Error('Invalid job id');
    }
    return this.http.get<UploadJobStatusResponse>(`${this.baseUrl}/upload/jobs/${encodeURIComponent(jobId)}`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getUploadAsyncState(): Observable<UploadAsyncStateResponse> {
    return this.http.get<UploadAsyncStateResponse>(`${this.baseUrl}/upload/state`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  cancelUploadJob(jobId: string): Observable<UploadCancelResponse> {
    if (!SecurityService.validateId(jobId)) {
      throw new Error('Invalid job id');
    }
    return this.http.post<UploadCancelResponse>(
      `${this.baseUrl}/upload/jobs/${encodeURIComponent(jobId)}/cancel`,
      null,
      { headers: this.auth.getAuthHeaders() }
    );
  }

  /** Admin-only: release a stuck cluster-wide upload lock. */
  forceReleaseUploadLock(): Observable<UploadCancelResponse> {
    return this.http.post<UploadCancelResponse>(`${this.baseUrl}/upload/admin/force-release`, null, {
      headers: this.auth.getAuthHeaders()
    });
  }

  listUploads(): Observable<UploadEntry[]> {
    return this.http.get<UploadEntry[]>(`${this.baseUrl}/uploads`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  downloadUploadJson(type: UploadEntry['type'], id: string): Observable<Blob> {
    // SECURITY: Validate path parameters
    if (type !== 'detailed' && type !== 'receivable') {
      throw new Error('Invalid upload type');
    }
    if (!SecurityService.validateId(id)) {
      throw new Error('Invalid ID parameter');
    }
    
    return this.http.get(`${this.baseUrl}/uploads/${type}/${id}/json`, {
      headers: this.auth.getAuthHeaders(),
      responseType: 'blob'
    });
  }

  downloadLatestJson(type: UploadEntry['type']): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/uploads/latest/${type}/json`, {
      headers: this.auth.getAuthHeaders(),
      responseType: 'blob'
    });
  }

  listUploadAudit(): Observable<UploadAuditEntry[]> {
    return this.http.get<UploadAuditEntry[]>(`${this.baseUrl}/uploads/audit`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getUploadStatus(): Observable<UploadStatusResponse> {
    return this.http.get<UploadStatusResponse>(`${this.baseUrl}/uploads/status`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getCustomerSuggestions(query: string, limit = 20): Observable<string[]> {
    // SECURITY: Validate and sanitize input
    if (!SecurityService.validateQuery(query, 3, 100)) {
      throw new Error('Invalid query parameter');
    }
    const sanitizedQuery = SecurityService.sanitizeQuery(query, 100);
    const validatedLimit = SecurityService.validateLimit(limit, 20, 100);
    
    // SECURITY: Use POST instead of GET to avoid sensitive data in URL
    return this.http.post<string[]>(`${this.baseUrl}/analytics/customers`, {
      query: sanitizedQuery,
      limit: validatedLimit
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getPhoneSuggestions(query: string, limit = 20): Observable<string[]> {
    // SECURITY: Use POST instead of GET to avoid sensitive data (phone numbers) in URL
    const sanitizedQuery = SecurityService.sanitizeQuery(query, 100);
    const validatedLimit = SecurityService.validateLimit(limit, 20, 100);
    
    return this.http.post<string[]>(`${this.baseUrl}/analytics/phone-suggestions`, {
      query: sanitizedQuery,
      limit: validatedLimit
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getVoucherSuggestions(query: string, limit = 20): Observable<string[]> {
    // SECURITY: Use POST instead of GET to avoid sensitive data (voucher numbers) in URL
    const sanitizedQuery = SecurityService.sanitizeQuery(query, 100);
    const validatedLimit = SecurityService.validateLimit(limit, 20, 100);
    
    return this.http.post<string[]>(`${this.baseUrl}/analytics/voucher-suggestions`, {
      query: sanitizedQuery,
      limit: validatedLimit
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getCustomerSummary(customer?: string, phone?: string): Observable<CustomerSummaryResponse> {
    // SECURITY: Use POST instead of GET to avoid sensitive data (customer names, phone numbers) in URL
    return this.http.post<CustomerSummaryResponse>(`${this.baseUrl}/analytics/customer-summary`, {
      customer: customer || null,
      phone: phone || null
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getCustomerLedger(customer?: string, phone?: string): Observable<CustomerLedgerEntry[]> {
    // SECURITY: Use POST instead of GET to avoid sensitive data (customer names, phone numbers) in URL
    return this.http.post<CustomerLedgerEntry[]>(`${this.baseUrl}/analytics/customer-ledger`, {
      customer: customer || null,
      phone: phone || null
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getOutstandingDue(): Observable<PaymentDateCustomerCard[]> {
    return this.http.get<PaymentDateCustomerCard[]>(`${this.baseUrl}/analytics/outstanding-due`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  updateNextPaymentDate(customer: string, nextPaymentDate: string, phoneNumber?: string | null, whatsAppStatus?: string | null): Observable<void> {
    const body: any = {
      customer,
      nextPaymentDate
    };
    if (phoneNumber !== undefined && phoneNumber !== null) {
      body.phoneNumber = phoneNumber;
    }
    if (whatsAppStatus !== undefined && whatsAppStatus !== null) {
      body.whatsAppStatus = whatsAppStatus;
    }
    return this.http.post<void>(`${this.baseUrl}/analytics/outstanding-due/next-date`, body, {
      headers: this.auth.getAuthHeaders()
    });
  }

  clearAllOutstandingDueDates(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/analytics/outstanding-due/clear`, null, {
      headers: this.auth.getAuthHeaders()
    });
  }

  updateWhatsAppStatus(customer: string, status: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/analytics/outstanding-due/whatsapp-status`, {
      customer,
      status
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  updateCustomerCategory(customer: string, category: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/analytics/outstanding-due/customer-category`, {
      customer,
      category
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  updatePlace(customer: string, place: string | null): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/analytics/outstanding-due/place`, {
      customer,
      place: place ?? null
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  updateFollowUpFlag(customer: string, needsFollowUp: boolean): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/analytics/outstanding-due/follow-up`, {
      customer,
      needsFollowUp
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getExcludedCustomers(): Observable<ExcludedCustomerView[]> {
    return this.http.get<ExcludedCustomerView[]>(`${this.baseUrl}/analytics/customers/excluded`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  excludeCustomer(customer: string): Observable<ExcludedCustomerView> {
    return this.http.post<ExcludedCustomerView>(`${this.baseUrl}/analytics/customers/exclude`, {
      customer
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  restoreExcludedCustomer(customerKey: string): Observable<ExcludedCustomerView> {
    return this.http.post<ExcludedCustomerView>(`${this.baseUrl}/analytics/customers/restore`, {
      customerKey
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getRetainedCustomers(): Observable<RetainedCustomerView[]> {
    return this.http.get<RetainedCustomerView[]>(`${this.baseUrl}/analytics/customers/retained`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  retainCustomer(customer: string): Observable<RetainedCustomerView> {
    return this.http.post<RetainedCustomerView>(`${this.baseUrl}/analytics/customers/retain`, {
      customer
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  unretainCustomer(customerKey: string): Observable<RetainedCustomerView> {
    return this.http.post<RetainedCustomerView>(`${this.baseUrl}/analytics/customers/unretain`, {
      customerKey
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  purgeUploads(): Observable<UploadPurgeResponse> {
    return this.http.post<UploadPurgeResponse>(`${this.baseUrl}/uploads/purge`, null, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getSalesInvoices(params: {
    customer?: string;
    phone?: string;
    voucherNo?: string;
    dateFrom?: string;
    dateTo?: string;
    receivedAmountMin?: number;
    receivedAmountMax?: number;
    currentDueMin?: number;
    currentDueMax?: number;
    ageingDaysMin?: number;
    ageingDaysMax?: number;
    ageingBucket?: string;
    totalAmountMin?: number;
    totalAmountMax?: number;
    year?: number;
    month?: number;
    quarter?: number;
    status?: string;
    sortBy?: string;
    sortOrder?: string;
    page?: number;
    size?: number;
  }): Observable<SalesInvoicePageResponse> {
    // SECURITY: Use POST instead of GET to avoid sensitive data (customer names, phone numbers, voucher numbers) in URL
    return this.http.post<SalesInvoicePageResponse>(`${this.baseUrl}/analytics/sales-invoices`, {
      customer: params.customer || null,
      phone: params.phone || null,
      voucherNo: params.voucherNo || null,
      dateFrom: params.dateFrom || null,
      dateTo: params.dateTo || null,
      receivedAmountMin: params.receivedAmountMin || null,
      receivedAmountMax: params.receivedAmountMax || null,
      currentDueMin: params.currentDueMin || null,
      currentDueMax: params.currentDueMax || null,
      ageingDaysMin: params.ageingDaysMin || null,
      ageingDaysMax: params.ageingDaysMax || null,
      ageingBucket: params.ageingBucket || null,
      totalAmountMin: params.totalAmountMin || null,
      totalAmountMax: params.totalAmountMax || null,
      year: params.year || null,
      month: params.month || null,
      quarter: params.quarter || null,
      status: params.status || null,
      sortBy: params.sortBy || null,
      sortOrder: params.sortOrder || 'asc',
      page: params.page !== undefined ? params.page : 0,
      size: params.size !== undefined ? params.size : 15
    }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  getRateListEntries(): Observable<RateListEntry[]> {
    return this.http.get<RateListEntry[]>(`${this.baseUrl}/rate-list`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  createRateListEntry(entry: RateListEntry): Observable<RateListEntry> {
    return this.http.post<RateListEntry>(`${this.baseUrl}/rate-list`, entry, {
      headers: this.auth.getAuthHeaders()
    });
  }

  updateRateListEntry(id: string, entry: RateListEntry): Observable<RateListEntry> {
    return this.http.put<RateListEntry>(`${this.baseUrl}/rate-list/${id}`, entry, {
      headers: this.auth.getAuthHeaders()
    });
  }

  deleteRateListEntry(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/rate-list/${id}`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  downloadRateListTemplate(): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/rate-list/template`, {
      headers: this.auth.getAuthHeaders(),
      responseType: 'blob'
    });
  }

  bulkUploadRateList(file: File): Observable<any> {
    // SECURITY: Validate file before upload
    const validation = SecurityService.validateFile(file);
    if (!validation.valid) {
      throw new Error(validation.error || 'Invalid file');
    }
    
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<any>(`${this.baseUrl}/rate-list/bulk-upload`, formData, {
      headers: this.auth.getAuthHeaders()
    });
  }

  migrateProductNames(): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/rate-list/migrate-product-names`, {}, {
      headers: this.auth.getAuthHeaders()
    });
  }

  updateProductSrNo(productName: string, srNo: number): Observable<any> {
    const authHeaders = this.auth.getAuthHeaders();
    const headers = authHeaders.set('Content-Type', 'application/json');
    return this.http.put<any>(`${this.baseUrl}/rate-list/product/${encodeURIComponent(productName)}/srno`, { srNo }, {
      headers: headers
    });
  }

  migrateSrNo(): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/rate-list/migrate-srno`, {}, {
      headers: this.auth.getAuthHeaders()
    });
  }

  // Location Management Methods
  updateCustomerLocation(customer: string, location: { address?: string | null, latitude?: number | null, longitude?: number | null }): Observable<void> {
    // Build request body: empty string for address deletion, null for coordinate deletion
    const body: any = { customer };
    if (location.address !== undefined) {
      body.address = location.address === null ? '' : location.address;
    }
    if (location.latitude !== undefined) {
      body.latitude = location.latitude;
    }
    if (location.longitude !== undefined) {
      body.longitude = location.longitude;
    }
    const authHeaders = this.auth.getAuthHeaders();
    const headers = authHeaders.set('Content-Type', 'application/json');
    return this.http.post<void>(`${this.baseUrl}/analytics/outstanding-due/location`, body, {
      headers: headers
    });
  }

  getCustomerLocations(): Observable<CustomerLocation[]> {
    return this.http.get<CustomerLocation[]>(`${this.baseUrl}/analytics/customers/locations`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  // Session Management Methods (Admin only)
  getAllSessions(): Observable<SessionListItem[]> {
    return this.http.get<SessionListItem[]>(`${this.baseUrl}/sessions`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  updateSession(userId: string, expiresAt: string): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/sessions/update`, { userId, expiresAt }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  deleteSession(userId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/sessions/delete`, { userId }, {
      headers: this.auth.getAuthHeaders()
    });
  }

  /** Admin: bump session epoch for every user and clear mirrored sessions — invalidates all JWTs server-side. */
  invalidateAllSessions(): Observable<{ usersInvalidated: number }> {
    return this.http.post<{ usersInvalidated: number }>(
      `${this.baseUrl}/sessions/invalidate-all`,
      {},
      { headers: this.auth.getAuthHeaders() }
    );
  }

  /** Admin: bump one user's session epoch — invalidates all their JWTs (not only Mongo mirror rows). */
  invalidateUserSessions(userId: string): Observable<void> {
    const headers = this.auth.getAuthHeaders().set('Content-Type', 'application/json');
    return this.http.post<void>(
      `${this.baseUrl}/sessions/invalidate-user`,
      { userId },
      { headers }
    );
  }

  /** Admin: clear Redis/memory login rate-limit counters for a username (optional IP). */
  unlockLoginLockouts(username: string, ip?: string): Observable<void> {
    const body: { username: string; ip?: string } = { username };
    if (ip) {
      body.ip = ip;
    }
    const headers = this.auth.getAuthHeaders().set('Content-Type', 'application/json');
    return this.http.post<void>(`${this.baseUrl}/sessions/unlock-login-attempts`, body, { headers });
  }

  getSalesVisualization(params?: {
    year?: number;
    month?: number;
    quarter?: number;
  }): Observable<SalesVisualizationResponse> {
    const httpParams: any = {};
    if (params?.year !== undefined) httpParams.year = params.year.toString();
    if (params?.month !== undefined) httpParams.month = params.month.toString();
    if (params?.quarter !== undefined) httpParams.quarter = params.quarter.toString();
    
    return this.http.get<SalesVisualizationResponse>(`${this.baseUrl}/analytics/sales-visualization`, {
      headers: this.auth.getAuthHeaders(),
      params: httpParams
    });
  }

  // Customer Notes API methods
  getCustomerNotes(request: CustomerNoteSearchRequest): Observable<CustomerNote[]> {
    // SECURITY: Validate input
    if ((!request.customerName || request.customerName.trim().length === 0) &&
        (!request.phoneNumber || request.phoneNumber.trim().length === 0)) {
      throw new Error('Either customerName or phoneNumber must be provided');
    }
    
    return this.http.post<CustomerNote[]>(`${this.baseUrl}/customer-notes/get`, request, {
      headers: this.auth.getAuthHeaders()
    });
  }

  createCustomerNote(request: CreateCustomerNoteRequest): Observable<CustomerNote> {
    // SECURITY: Validate input
    if ((!request.customerName || request.customerName.trim().length === 0) &&
        (!request.phoneNumber || request.phoneNumber.trim().length === 0)) {
      throw new Error('Either customerName or phoneNumber must be provided');
    }
    if (!request.note || request.note.trim().length === 0) {
      throw new Error('Note content is required');
    }
    
    return this.http.post<CustomerNote>(`${this.baseUrl}/customer-notes/create`, request, {
      headers: this.auth.getAuthHeaders()
    });
  }

  updateCustomerNote(request: UpdateCustomerNoteRequest): Observable<CustomerNote> {
    // SECURITY: Validate input
    if (!request.noteId || request.noteId.trim().length === 0) {
      throw new Error('Note ID is required');
    }
    if (!request.note || request.note.trim().length === 0) {
      throw new Error('Note content is required');
    }
    
    return this.http.post<CustomerNote>(`${this.baseUrl}/customer-notes/update`, request, {
      headers: this.auth.getAuthHeaders()
    });
  }

  deleteCustomerNote(request: DeleteCustomerNoteRequest): Observable<void> {
    // SECURITY: Validate input
    if (!request.noteId || request.noteId.trim().length === 0) {
      throw new Error('Note ID is required');
    }
    
    return this.http.post<void>(`${this.baseUrl}/customer-notes/delete`, request, {
      headers: this.auth.getAuthHeaders()
    });
  }

  listWhatsappBroadcasts(): Observable<WhatsappBroadcastBatchSummaryDto[]> {
    return this.http.get<WhatsappBroadcastBatchSummaryDto[]>(`${this.baseUrl}/whatsapp/broadcasts`, {
      headers: this.auth.getAuthHeaders()
    });
  }

  createWhatsappBroadcast(body: WhatsappBroadcastCreateRequestDto): Observable<WhatsappBroadcastBatchResponseDto> {
    return this.http.post<WhatsappBroadcastBatchResponseDto>(
      `${this.baseUrl}/whatsapp/broadcasts`,
      body,
      { headers: this.auth.getAuthHeaders() }
    );
  }

  getWhatsappBroadcast(batchId: string): Observable<WhatsappBroadcastBatchResponseDto> {
    return this.http.get<WhatsappBroadcastBatchResponseDto>(
      `${this.baseUrl}/whatsapp/broadcasts/${encodeURIComponent(batchId)}`,
      { headers: this.auth.getAuthHeaders() }
    );
  }

  getWhatsappWaLink(batchId: string, recipientId: string, markOpened = true): Observable<WhatsappWaLinkResponseDto> {
    return this.http.get<WhatsappWaLinkResponseDto>(
      `${this.baseUrl}/whatsapp/broadcasts/${encodeURIComponent(batchId)}/recipients/${encodeURIComponent(recipientId)}/wa-link`,
      { headers: this.auth.getAuthHeaders(), params: { markOpened: String(markOpened) } }
    );
  }

  patchWhatsappRecipient(
    batchId: string,
    recipientId: string,
    body: { status: string; failureReason?: string | null }
  ): Observable<WhatsappBroadcastRecipientResponseDto> {
    return this.http.patch<WhatsappBroadcastRecipientResponseDto>(
      `${this.baseUrl}/whatsapp/broadcasts/${encodeURIComponent(batchId)}/recipients/${encodeURIComponent(recipientId)}`,
      body,
      { headers: this.auth.getAuthHeaders() }
    );
  }
}


export interface MonthlyTrendData {
  month: string;
  revenue: number;
}

export interface CustomerRevenueData {
  customer: string;
  revenue: number;
}

export interface PaymentStatusData {
  status: string;
  count: number;
}

export interface AgeingBucketData {
  bucket: string;
  amount: number;
}

export interface SalesVisualizationResponse {
  monthlyTrends: MonthlyTrendData[];
  topCustomers: CustomerRevenueData[];
  paymentStatusDistribution: PaymentStatusData[];
  ageingBucketAmounts: AgeingBucketData[];
  additionalMetrics: any[];
  totalRevenue: number;
  totalReceived: number;
  totalOutstanding: number;
  collectionRate: number;
  totalInvoices: number;
}


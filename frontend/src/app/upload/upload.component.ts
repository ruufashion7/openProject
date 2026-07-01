import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subject, Subscription, merge, of, timer } from 'rxjs';
import { catchError, filter, switchMap, take, takeUntil, tap } from 'rxjs/operators';
import {
  ApiService,
  UploadAsyncStateResponse,
  UploadConflictResponse,
  UploadCurrentJobResponse,
  UploadJobStatusResponse,
  UploadLastOutcomeResponse
} from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { SecurityService } from '../security/security.service';

@Component({
  selector: 'app-upload',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './upload.component.html',
  styleUrl: './upload.component.css'
})
export class UploadComponent implements OnInit, OnDestroy {
  file1?: File;
  file2?: File;
  /** This browser session's upload attempt (idle until user submits or resumes an active job). */
  sessionStatus: 'idle' | 'loading' | 'success' | 'failed' | 'cancelled' = 'idle';
  message = '';
  uploadedFiles: Array<{ id: string; filename: string }> = [];
  canUpload = false;
  isAdmin = false;

  lastServerOutcome: UploadLastOutcomeResponse | null = null;
  serverBusy = false;
  serverCurrentJob: UploadCurrentJobResponse | null = null;

  private trackedJobId: string | null = null;
  /** Job started by this browser session (not merely observing). */
  private ownJobId: string | null = null;
  pollUi: UploadJobStatusResponse | null = null;
  releasingLock = false;

  private readonly destroy$ = new Subject<void>();
  private readonly uploadOpCancel$ = new Subject<void>();
  private pollSubscription?: Subscription;

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router,
    private permissionService: PermissionService,
    private notificationService: NotificationService
  ) {}

  ngOnInit(): void {
    this.canUpload = this.permissionService.canAccessFileUpload();
    this.isAdmin = this.permissionService.isAdmin();
    if (!this.canUpload) {
      this.notificationService.showPermissionError();
      this.router.navigateByUrl('/welcome');
      return;
    }
    this.startAsyncStateRefresh();
  }

  ngOnDestroy(): void {
    this.resetUploadOperation();
    this.stopPolling();
    this.destroy$.next();
    this.destroy$.complete();
    this.uploadOpCancel$.complete();
  }

  private resetUploadOperation(): void {
    this.uploadOpCancel$.next();
  }

  private stopPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = undefined;
  }

  private startAsyncStateRefresh(): void {
    merge(timer(0, 3000))
      .pipe(
        takeUntil(this.destroy$),
        switchMap(() =>
          this.api.getUploadAsyncState().pipe(
            catchError(() => of(null as UploadAsyncStateResponse | null))
          )
        )
      )
      .subscribe((s) => {
        if (!s) {
          return;
        }
        this.applyAsyncState(s);
      });
  }

  private applyAsyncState(s: UploadAsyncStateResponse): void {
    this.lastServerOutcome = s.lastOutcome;
    const wasBusy = this.serverBusy;
    this.serverBusy = s.busy;
    this.serverCurrentJob = s.currentJob;

    const activeJobId = s.currentJob?.jobId;
    if (s.busy && activeJobId && SecurityService.validateId(activeJobId)) {
      if (this.trackedJobId !== activeJobId) {
        this.ensureJobPolling(activeJobId, false);
      }
      if (this.sessionStatus === 'idle' || (this.sessionStatus === 'failed' && !this.ownJobId)) {
        this.sessionStatus = 'loading';
        this.message =
          s.currentJob?.message ||
          'An upload is in progress on the server. New uploads are blocked until it finishes.';
      }
    } else if (wasBusy && !s.busy && this.sessionStatus === 'loading' && !this.ownJobId) {
      this.sessionStatus = 'idle';
      this.message = '';
      this.pollUi = null;
      this.trackedJobId = null;
      this.stopPolling();
    }
  }

  onFileChange(event: Event, slot: 'file1' | 'file2'): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }

    const validation = SecurityService.validateFile(file);
    if (!validation.valid) {
      this.sessionStatus = 'failed';
      this.message = validation.error || 'Invalid file';
      input.value = '';
      return;
    }

    if (slot === 'file1') {
      this.file1 = file;
    } else {
      this.file2 = file;
    }
  }

  submit(): void {
    this.message = '';
    this.uploadedFiles = [];
    this.pollUi = null;
    this.ownJobId = null;

    if (!this.file1 || !this.file2) {
      this.sessionStatus = 'failed';
      this.message = 'Please select both files before uploading.';
      return;
    }

    this.resetUploadOperation();
    this.stopPolling();
    this.sessionStatus = 'loading';
    this.message = 'Checking server…';
    this.trackedJobId = null;

    const opCancel$ = this.uploadOpCancel$;
    this.api
      .getUploadAsyncState()
      .pipe(
        switchMap((state) => {
          this.applyAsyncState(state);
          if (state.busy) {
            const err = new HttpErrorResponse({
              status: 409,
              error: {
                status: 'failed',
                message:
                  'An upload is already in progress. Wait until it finishes, cancel it from the upload page, or poll GET /api/upload/state.',
                currentJobId: state.currentJob?.jobId ?? null
              } as UploadConflictResponse
            });
            throw err;
          }
          this.message = 'Sending files to server…';
          return this.api.uploadFiles(this.file1!, this.file2!);
        }),
        switchMap((accepted) => {
          this.ownJobId = accepted.jobId;
          this.trackedJobId = accepted.jobId;
          this.message =
            'Processing on server. You can leave this page; open Latest Uploads when ready.';
          return this.pollUntilTerminal(accepted.jobId, opCancel$);
        }),
        takeUntil(opCancel$),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: (job) => this.applyTerminalJob(job),
        error: (err: HttpErrorResponse) => this.handleUploadHttpError(err)
      });
  }

  cancelUpload(): void {
    const id = this.trackedJobId ?? this.pollUi?.jobId ?? this.serverCurrentJob?.jobId;
    if (!id) {
      return;
    }
    const cancellable = this.pollUi?.cancellable ?? this.serverCurrentJob?.cancellable;
    if (!cancellable) {
      this.message = 'Stop is only available while the server is reading Excel (parsing phase).';
      return;
    }
    this.message = 'Requesting stop…';
    this.api
      .cancelUploadJob(id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => {
          this.message = res.message;
        },
        error: (err: HttpErrorResponse) => {
          const body = err.error as { message?: string } | null;
          this.message = body?.message ?? 'Could not cancel.';
        }
      });
  }

  forceReleaseLock(): void {
    if (!this.isAdmin || this.releasingLock) {
      return;
    }
    if (
      !window.confirm(
        'Release the cluster-wide upload lock? Only do this if an upload is stuck and blocking everyone.'
      )
    ) {
      return;
    }
    this.releasingLock = true;
    this.resetUploadOperation();
    this.api
      .forceReleaseUploadLock()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => {
          this.releasingLock = false;
          this.message = res.message;
          this.sessionStatus = 'cancelled';
          this.pollUi = null;
          this.trackedJobId = null;
          this.ownJobId = null;
          this.stopPolling();
          this.api.getUploadAsyncState().pipe(takeUntil(this.destroy$)).subscribe((s) => this.applyAsyncState(s));
          this.notificationService.showSuccess(res.message, 6000);
        },
        error: (err: HttpErrorResponse) => {
          this.releasingLock = false;
          const body = err.error as { message?: string } | null;
          this.message = body?.message ?? 'Could not release the upload lock.';
          this.sessionStatus = 'failed';
        }
      });
  }

  private ensureJobPolling(jobId: string, setLoadingMessage: boolean): void {
    if (this.trackedJobId === jobId && this.pollSubscription) {
      return;
    }
    this.trackedJobId = jobId;
    if (setLoadingMessage) {
      this.sessionStatus = 'loading';
    }
    this.stopPolling();
    const opCancel$ = this.uploadOpCancel$;
    this.pollSubscription = this.buildJobPollStream(jobId, opCancel$).subscribe({
      next: (job) => this.applyTerminalJob(job),
      error: (err: HttpErrorResponse) => {
        this.sessionStatus = 'failed';
        this.message = this.describePollError(err);
        this.trackedJobId = null;
        this.pollUi = null;
      }
    });
  }

  private buildJobPollStream(jobId: string, opCancel$: Subject<void>): Observable<UploadJobStatusResponse> {
    return timer(0, 2000).pipe(
      takeUntil(this.destroy$),
      takeUntil(opCancel$),
      switchMap(() => this.api.getUploadJobStatus(jobId)),
      tap((s) => {
        this.pollUi = s;
        if (s.state === 'processing') {
          this.message = s.message;
        }
      }),
      filter((s) => s.state === 'success' || s.state === 'failed' || s.state === 'cancelled'),
      take(1)
    );
  }

  private pollUntilTerminal(
    jobId: string,
    opCancel$: Subject<void>
  ): Observable<UploadJobStatusResponse> {
    this.trackedJobId = jobId;
    this.stopPolling();
    return this.buildJobPollStream(jobId, opCancel$);
  }

  private applyTerminalJob(job: UploadJobStatusResponse): void {
    this.trackedJobId = null;
    this.pollUi = job;
    this.uploadedFiles = job.files ?? [];

    const isOwn = this.ownJobId === job.jobId;
    this.api
      .getUploadAsyncState()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (s) => {
          this.applyAsyncState(s);
          if (s.busy) {
            this.sessionStatus = 'failed';
            this.message =
              'The server still has an upload lock active (job may be stuck). Use Stop upload, wait about a minute, or ask an admin to release the lock.';
            if (s.currentJob?.jobId && SecurityService.validateId(s.currentJob.jobId)) {
              this.ensureJobPolling(s.currentJob.jobId, false);
            }
            return;
          }

          if (!isOwn) {
            this.sessionStatus = 'idle';
            this.message = `Import finished (${job.state}) — started by ${job.startedByDisplayName || 'another user'}.`;
            return;
          }

          if (job.state === 'success') {
            this.sessionStatus = 'success';
          } else if (job.state === 'cancelled') {
            this.sessionStatus = 'cancelled';
          } else {
            this.sessionStatus = 'failed';
          }
          this.message = job.message;
          this.ownJobId = null;
        },
        error: () => {
          if (job.state === 'success') {
            this.sessionStatus = 'success';
          } else if (job.state === 'cancelled') {
            this.sessionStatus = 'cancelled';
          } else {
            this.sessionStatus = 'failed';
          }
          this.message = job.message;
          this.ownJobId = null;
        }
      });
  }

  private handleUploadHttpError(err: HttpErrorResponse): void {
    this.trackedJobId = null;
    this.pollUi = null;
    this.ownJobId = null;

    if (err.status === 409 && err.error && typeof err.error === 'object') {
      const body = err.error as UploadConflictResponse;
      this.message =
        body.message ||
        'An upload is already in progress. Wait for it to finish or cancel it if you have access.';
      if (body.currentJobId && SecurityService.validateId(body.currentJobId)) {
        this.ensureJobPolling(body.currentJobId, true);
      } else {
        this.sessionStatus = 'failed';
      }
      return;
    }

    this.sessionStatus = 'failed';

    if (err.error && typeof err.error === 'object' && 'message' in err.error) {
      const body = err.error as { message?: string };
      this.message = body.message ?? 'Upload failed.';
    } else if (err.status === 413) {
      this.message =
        'File is too large. The Excel file contains too much data. Please reduce the file size or split the data.';
    } else if (err.status === 401) {
      this.message = 'Session expired. Please login again.';
      this.logout();
      return;
    } else if (err.status === 502 || err.status === 504) {
      this.message =
        'Gateway timed out while sending files. If the files are very large, try again or ask your admin to increase proxy timeouts.';
    } else {
      this.message = 'Upload failed. Please try again.';
    }
  }

  private describePollError(err: HttpErrorResponse): string {
    if (err.status === 401) {
      this.logout();
      return 'Session expired. Please login again.';
    }
    if (err.status === 404) {
      return 'Upload job not found. It may have expired; check Latest Uploads or try uploading again.';
    }
    return 'Lost connection to upload status. Refresh the page or check Latest Uploads.';
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  get primaryButtonLabel(): string {
    if (this.uploadBlocked) {
      return this.serverBusy && !this.ownJobId ? 'Upload blocked' : 'Working…';
    }
    return 'Upload Files';
  }

  get uploadBlocked(): boolean {
    return this.serverBusy || this.sessionStatus === 'loading';
  }

  get canStopUpload(): boolean {
    const cancellable = this.pollUi?.cancellable ?? this.serverCurrentJob?.cancellable;
    return this.serverBusy && !!cancellable;
  }

  get showForceRelease(): boolean {
    return this.isAdmin && this.serverBusy;
  }

  get activePhaseLabel(): string {
    return this.pollUi?.phase ?? this.serverCurrentJob?.phase ?? '—';
  }

  get activeStartedBy(): string {
    return this.pollUi?.startedByDisplayName ?? this.serverCurrentJob?.startedByDisplayName ?? '—';
  }

  get showSessionStatus(): boolean {
    return this.sessionStatus !== 'idle';
  }
}

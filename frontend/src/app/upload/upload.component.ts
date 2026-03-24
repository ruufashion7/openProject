import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subject, Subscription, timer } from 'rxjs';
import { filter, switchMap, take, takeUntil, tap } from 'rxjs/operators';
import {
  ApiService,
  UploadAsyncStateResponse,
  UploadConflictResponse,
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
  status: 'idle' | 'loading' | 'success' | 'failed' | 'cancelled' = 'idle';
  message = '';
  uploadedFiles: Array<{ id: string; filename: string }> = [];
  canUpload = false;

  /** Server-wide last terminal outcome (success, failed, cancelled). */
  lastServerOutcome: UploadLastOutcomeResponse | null = null;

  /** Job id we are polling (this session or resumed). */
  private trackedJobId: string | null = null;

  /** Latest poll snapshot while loading — drives phase message and cancel button. */
  pollUi: UploadJobStatusResponse | null = null;

  private readonly destroy$ = new Subject<void>();
  private pollSubscription?: Subscription;
  private initialResumeDone = false;

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router,
    private permissionService: PermissionService,
    private notificationService: NotificationService
  ) {}

  ngOnInit(): void {
    this.canUpload = this.permissionService.canAccessFileUpload();
    if (!this.canUpload) {
      this.notificationService.showPermissionError();
      this.router.navigateByUrl('/welcome');
      return;
    }
    this.loadAsyncStateAndMaybeResume();
  }

  ngOnDestroy(): void {
    this.stopPolling();
    this.destroy$.next();
    this.destroy$.complete();
  }

  private stopPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = undefined;
  }

  private loadAsyncStateAndMaybeResume(): void {
    this.api
      .getUploadAsyncState()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (s: UploadAsyncStateResponse) => {
          this.lastServerOutcome = s.lastOutcome;
          if (this.initialResumeDone) {
            return;
          }
          this.initialResumeDone = true;
          if (this.status !== 'idle') {
            return;
          }
          if (s.busy && s.currentJob?.jobId && SecurityService.validateId(s.currentJob.jobId)) {
            this.status = 'loading';
            this.message = 'An upload is already in progress (you or another user).';
            this.startPollingJob(s.currentJob.jobId);
          }
        },
        error: () => {
          /* non-blocking */
        }
      });
  }

  private refreshLastOutcome(): void {
    this.api
      .getUploadAsyncState()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (s) => {
          this.lastServerOutcome = s.lastOutcome;
        },
        error: () => {}
      });
  }

  onFileChange(event: Event, slot: 'file1' | 'file2'): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }

    const validation = SecurityService.validateFile(file);
    if (!validation.valid) {
      this.status = 'failed';
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

    if (!this.file1 || !this.file2) {
      this.status = 'failed';
      this.message = 'Please select both files before uploading.';
      return;
    }

    this.stopPolling();
    this.status = 'loading';
    this.message = 'Sending files to server…';
    this.trackedJobId = null;

    this.api
      .uploadFiles(this.file1, this.file2)
      .pipe(
        switchMap((accepted) => {
          this.trackedJobId = accepted.jobId;
          this.message =
            'Processing on server. You can leave this page; open Latest Uploads when ready. Another upload cannot start until this one finishes.';
          return this.pollUntilTerminal(accepted.jobId);
        }),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: (job) => this.applyTerminalJob(job),
        error: (err: HttpErrorResponse) => this.handleUploadHttpError(err)
      });
  }

  cancelUpload(): void {
    const id = this.trackedJobId ?? this.pollUi?.jobId;
    if (!id || !this.pollUi?.cancellable) {
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
          if (err.error && typeof err.error === 'object' && 'message' in err.error) {
            const body = err.error as { message?: string };
            this.message = body.message ?? 'Could not cancel.';
          } else {
            this.message = 'Could not cancel. Try again.';
          }
        }
      });
  }

  private startPollingJob(jobId: string): void {
    this.stopPolling();
    this.trackedJobId = jobId;
    this.pollSubscription = timer(0, 2000)
      .pipe(
        takeUntil(this.destroy$),
        switchMap(() => this.api.getUploadJobStatus(jobId)),
        tap((s) => {
          this.pollUi = s;
          if (s.state === 'processing') {
            this.message = s.message;
          }
        }),
        filter((s) => s.state === 'success' || s.state === 'failed' || s.state === 'cancelled'),
        take(1)
      )
      .subscribe({
        next: (job) => this.applyTerminalJob(job),
        error: (err: HttpErrorResponse) => {
          this.status = 'failed';
          this.message = this.describePollError(err);
          this.trackedJobId = null;
          this.pollUi = null;
        }
      });
  }

  private pollUntilTerminal(jobId: string): Observable<UploadJobStatusResponse> {
    return timer(0, 2000).pipe(
      takeUntil(this.destroy$),
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

  private applyTerminalJob(job: UploadJobStatusResponse): void {
    this.trackedJobId = null;
    this.pollUi = job;
    if (job.state === 'success') {
      this.status = 'success';
    } else if (job.state === 'cancelled') {
      this.status = 'cancelled';
    } else {
      this.status = 'failed';
    }
    this.message = job.message;
    this.uploadedFiles = job.files ?? [];
    this.refreshLastOutcome();
  }

  private handleUploadHttpError(err: HttpErrorResponse): void {
    this.status = 'failed';
    this.trackedJobId = null;
    this.pollUi = null;

    if (err.status === 409 && err.error && typeof err.error === 'object') {
      const body = err.error as UploadConflictResponse;
      this.message =
        body.message ||
        'An upload is already in progress. Wait for it to finish or cancel it if you have access.';
      if (body.currentJobId && SecurityService.validateId(body.currentJobId)) {
        this.status = 'loading';
        this.startPollingJob(body.currentJobId);
      }
      return;
    }

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
    return this.status === 'loading' ? 'Working…' : 'Upload Files';
  }

  get canStopUpload(): boolean {
    return this.status === 'loading' && !!this.pollUi?.cancellable;
  }
}

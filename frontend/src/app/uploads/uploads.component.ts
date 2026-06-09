import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService, UploadEntry } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { PageStateComponent } from '../shared/page-state/page-state.component';
import { messageFromHttpError } from '../shared/api-error.util';

@Component({
  selector: 'app-uploads',
  standalone: true,
  imports: [CommonModule, RouterLink, PageStateComponent],
  templateUrl: './uploads.component.html',
  styleUrl: './uploads.component.css'
})
export class UploadsComponent implements OnInit {
  entries: UploadEntry[] = [];
  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router,
    private permissionService: PermissionService,
    private notificationService: NotificationService
  ) {}

  ngOnInit(): void {
    if (!this.permissionService.canAccessUploadsList()) {
      this.notificationService.showPermissionError();
      this.router.navigateByUrl('/welcome');
      return;
    }
    this.loadBatches();
  }

  loadBatches(): void {
    this.status = 'loading';
    this.message = '';
    this.api.listUploads().subscribe({
      next: (entries) => {
        this.entries = entries;
        this.status = 'idle';
      },
      error: (err: HttpErrorResponse) => {
        this.status = 'failed';
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        this.message = messageFromHttpError(err, 'Failed to load uploads.');
        this.notificationService.showError(this.message);
      }
    });
  }

  downloadLatest(type: UploadEntry['type']): void {
    this.api.downloadLatestJson(type).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `upload-${type}-latest.json`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: (err: HttpErrorResponse) => {
        this.status = 'failed';
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        this.message = 'Latest download failed.';
      }
    });
  }

  getEntry(type: UploadEntry['type']): UploadEntry | undefined {
    return this.entries.find((entry) => entry.type === type);
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}


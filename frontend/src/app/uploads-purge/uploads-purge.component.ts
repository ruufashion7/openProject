import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService, UploadPurgeResponse } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { messageFromHttpError } from '../shared/api-error.util';

@Component({
  selector: 'app-uploads-purge',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './uploads-purge.component.html',
  styleUrl: './uploads-purge.component.css'
})
export class UploadsPurgeComponent implements OnInit {
  status: 'idle' | 'loading' | 'success' | 'failed' = 'idle';
  message = '';
  result?: UploadPurgeResponse;
  confirm = false;
  canDelete = false;

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router,
    private permissionService: PermissionService,
    private notificationService: NotificationService
  ) {}

  ngOnInit(): void {
    this.canDelete = this.permissionService.canAccessHardDelete();
    if (!this.canDelete) {
      this.notificationService.showPermissionError();
      this.router.navigateByUrl('/welcome');
    }
  }

  purge(): void {
    if (!this.confirm) {
      this.status = 'failed';
      this.message = 'Please confirm before deleting.';
      return;
    }
    if (!window.confirm('Delete all current data for both upload files? This cannot be undone.')) {
      return;
    }
    this.status = 'loading';
    this.message = '';
    this.result = undefined;
    this.api.purgeUploads().subscribe({
      next: (response) => {
        this.status = 'success';
        this.result = response;
        this.message = 'Data deleted successfully.';
      },
      error: (err: HttpErrorResponse) => {
        this.status = 'failed';
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        this.message = messageFromHttpError(err, 'Delete failed.');
        this.notificationService.showError(this.message);
      }
    });
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}


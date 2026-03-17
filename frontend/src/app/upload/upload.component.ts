import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService } from '../services/api.service';
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
export class UploadComponent implements OnInit {
  file1?: File;
  file2?: File;
  status: 'idle' | 'loading' | 'success' | 'failed' = 'idle';
  message = '';
  uploadedFiles: Array<{ id: string; filename: string }> = [];
  canUpload = false;

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
    }
  }

  onFileChange(event: Event, slot: 'file1' | 'file2'): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    
    // SECURITY: Validate file before accepting
    const validation = SecurityService.validateFile(file);
    if (!validation.valid) {
      this.status = 'failed';
      this.message = validation.error || 'Invalid file';
      // Clear the input
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

    if (!this.file1 || !this.file2) {
      this.status = 'failed';
      this.message = 'Please select both files before uploading.';
      return;
    }

    this.status = 'loading';
    this.api.uploadFiles(this.file1, this.file2).subscribe({
      next: (response) => {
        this.status = response.status;
        this.message = response.message;
        this.uploadedFiles = response.files ?? [];
      },
      error: (err: HttpErrorResponse) => {
        this.status = 'failed';
        // Try to extract error message from response if available
        if (err.error && err.error.message) {
          this.message = err.error.message;
        } else if (err.status === 413 || err.status === 413) {
          this.message = 'File is too large. The Excel file contains too much data. Please reduce the file size or split the data.';
        } else if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        } else {
          this.message = 'Upload failed. Please try again.';
        }
      }
    });
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}


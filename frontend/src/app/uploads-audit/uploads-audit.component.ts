import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService, UploadAuditEntry } from '../services/api.service';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-uploads-audit',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './uploads-audit.component.html',
  styleUrl: './uploads-audit.component.css'
})
export class UploadsAuditComponent implements OnInit {
  entries: UploadAuditEntry[] = [];
  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadAudit();
  }

  loadAudit(): void {
    this.status = 'loading';
    this.message = '';
    this.api.listUploadAudit().subscribe({
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
        this.message = 'Failed to load audit trail.';
      }
    });
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}


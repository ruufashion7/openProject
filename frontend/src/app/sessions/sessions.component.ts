import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService, SessionListItem } from '../services/api.service';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-sessions',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './sessions.component.html',
  styleUrl: './sessions.component.css'
})
export class SessionsComponent implements OnInit {
  sessions: SessionListItem[] = [];
  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';
  editingToken: string | null = null;
  newExpiryDate: string = '';
  newExpiryTime: string = '';

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadSessions();
  }

  loadSessions(): void {
    this.status = 'loading';
    this.message = '';
    this.api.getAllSessions().subscribe({
      next: (sessions) => {
        this.sessions = sessions;
        this.status = 'idle';
      },
      error: (err: HttpErrorResponse) => {
        this.status = 'failed';
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        if (err.status === 403) {
          this.message = 'Access denied. Admin privileges required.';
        } else {
          this.message = 'Failed to load sessions.';
        }
      }
    });
  }

  startEdit(session: SessionListItem): void {
    this.editingToken = session.token;
    const expiryDate = new Date(session.expiresAt);
    this.newExpiryDate = expiryDate.toISOString().split('T')[0];
    this.newExpiryTime = expiryDate.toTimeString().split(' ')[0].substring(0, 5);
  }

  cancelEdit(): void {
    this.editingToken = null;
    this.newExpiryDate = '';
    this.newExpiryTime = '';
  }

  saveSession(token: string): void {
    if (!this.newExpiryDate || !this.newExpiryTime) {
      this.message = 'Please provide both date and time.';
      return;
    }

    const expiryDateTime = new Date(`${this.newExpiryDate}T${this.newExpiryTime}:00`);
    const expiresAt = expiryDateTime.toISOString();

    this.api.updateSession(token, expiresAt).subscribe({
      next: () => {
        this.message = 'Session updated successfully.';
        this.cancelEdit();
        this.loadSessions();
        setTimeout(() => this.message = '', 3000);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        this.message = 'Failed to update session.';
        setTimeout(() => this.message = '', 3000);
      }
    });
  }

  deleteSession(token: string): void {
    if (!confirm('Are you sure you want to delete this session? The user will be logged out.')) {
      return;
    }

    this.api.deleteSession(token).subscribe({
      next: () => {
        this.message = 'Session deleted successfully.';
        this.loadSessions();
        setTimeout(() => this.message = '', 3000);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.message = 'Session expired. Please login again.';
          this.logout();
          return;
        }
        this.message = 'Failed to delete session.';
        setTimeout(() => this.message = '', 3000);
      }
    });
  }

  extendAllSessions(minutes: number): void {
    if (!confirm(`Extend all sessions by ${minutes} minutes?`)) {
      return;
    }

    const now = new Date();
    const newExpiry = new Date(now.getTime() + minutes * 60 * 1000);
    const expiresAt = newExpiry.toISOString();

    let completed = 0;
    let failed = 0;

    this.sessions.forEach(session => {
      if (!session.isExpired) {
        this.api.updateSession(session.token, expiresAt).subscribe({
          next: () => {
            completed++;
            if (completed + failed === this.sessions.filter(s => !s.isExpired).length) {
              this.message = `Extended ${completed} session(s) by ${minutes} minutes.`;
              this.loadSessions();
              setTimeout(() => this.message = '', 5000);
            }
          },
          error: () => {
            failed++;
            if (completed + failed === this.sessions.filter(s => !s.isExpired).length) {
              this.message = `Extended ${completed} session(s). ${failed} failed.`;
              this.loadSessions();
              setTimeout(() => this.message = '', 5000);
            }
          }
        });
      }
    });
  }

  formatDate(dateString: string): string {
    const date = new Date(dateString);
    return date.toLocaleString();
  }

  isExpiringSoon(expiresAt: string): boolean {
    const expiry = new Date(expiresAt);
    const now = new Date();
    const minutesUntilExpiry = (expiry.getTime() - now.getTime()) / (1000 * 60);
    return minutesUntilExpiry > 0 && minutesUntilExpiry <= 10;
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}


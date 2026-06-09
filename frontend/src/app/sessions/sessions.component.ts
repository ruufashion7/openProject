import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService, SessionListItem } from '../services/api.service';
import { AuthService } from '../auth/auth.service';
import { PageStateComponent } from '../shared/page-state/page-state.component';
import { NotificationService } from '../shared/notification.service';

@Component({
  selector: 'app-sessions',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, PageStateComponent],
  templateUrl: './sessions.component.html',
  styleUrl: './sessions.component.css'
})
export class SessionsComponent implements OnInit {
  sessions: SessionListItem[] = [];
  status: 'idle' | 'loading' | 'failed' = 'idle';
  message = '';
  editingUserId: string | null = null;
  newExpiryDate: string = '';
  newExpiryTime: string = '';

  constructor(
    private api: ApiService,
    private auth: AuthService,
    private router: Router,
    private notifications: NotificationService
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
        this.notifications.showError(this.message);
      }
    });
  }

  startEdit(session: SessionListItem): void {
    this.editingUserId = session.userId;
    const expiryDate = new Date(session.expiresAt);
    this.newExpiryDate = expiryDate.toISOString().split('T')[0];
    this.newExpiryTime = expiryDate.toTimeString().split(' ')[0].substring(0, 5);
  }

  cancelEdit(): void {
    this.editingUserId = null;
    this.newExpiryDate = '';
    this.newExpiryTime = '';
  }

  saveSession(userId: string): void {
    if (!this.newExpiryDate || !this.newExpiryTime) {
      this.message = 'Please provide both date and time.';
      return;
    }

    const expiryDateTime = new Date(`${this.newExpiryDate}T${this.newExpiryTime}:00`);
    const expiresAt = expiryDateTime.toISOString();

    this.api.updateSession(userId, expiresAt).subscribe({
      next: () => {
        this.notifications.showSuccess('Session updated successfully.');
        this.cancelEdit();
        this.loadSessions();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.logout();
          return;
        }
        this.notifications.showError('Failed to update session.');
      }
    });
  }

  unlockLoginLockouts(): void {
    const username = window.prompt('Username to unlock (clear failed-login rate limits):', '');
    if (!username || !username.trim()) {
      return;
    }
    const ip = window.prompt('Optional IP to unlock (leave empty to only clear username bucket):', '') ?? '';
    this.api.unlockLoginLockouts(username.trim(), ip.trim() || undefined).subscribe({
      next: () => {
        this.notifications.showSuccess(`Login lockouts cleared for ${username.trim()}.`);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.logout();
          return;
        }
        this.notifications.showError(err.status === 403 ? 'Admin only.' : 'Unlock failed.');
      }
    });
  }

  invalidateAllSessions(): void {
    if (
      !confirm(
        'This invalidates every login token on the server (not only rows in this list). You and every user will need to sign in again. Continue?'
      )
    ) {
      return;
    }
    this.api.invalidateAllSessions().subscribe({
      next: () => {
        this.notifications.showSuccess('All sessions invalidated.');
        this.logout();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.logout();
          return;
        }
        this.notifications.showError(err.status === 403 ? 'Access denied.' : 'Failed to invalidate all sessions.');
      }
    });
  }

  invalidateUserSessions(userId: string): void {
    if (!userId) {
      return;
    }
    if (
      !confirm(
        'Invalidate every token for this user? They will be signed out on all devices (JWT epoch bump).'
      )
    ) {
      return;
    }
    this.api.invalidateUserSessions(userId).subscribe({
      next: () => {
        this.notifications.showSuccess('All tokens for this user were invalidated.');
        this.loadSessions();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.logout();
          return;
        }
        this.notifications.showError(err.error?.error ?? 'Failed to invalidate user sessions.');
      }
    });
  }

  deleteSession(userId: string): void {
    if (!confirm('Are you sure you want to delete this session? The user will be logged out.')) {
      return;
    }

    this.api.deleteSession(userId).subscribe({
      next: () => {
        this.notifications.showSuccess('Session deleted successfully.');
        this.loadSessions();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 401) {
          this.logout();
          return;
        }
        this.notifications.showError('Failed to delete session.');
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
    const active = this.sessions.filter((s) => !s.isExpired);

    active.forEach((session) => {
      this.api.updateSession(session.userId, expiresAt).subscribe({
        next: () => {
          completed++;
          if (completed + failed === active.length) {
            this.notifications.showSuccess(`Extended ${completed} session(s) by ${minutes} minutes.`);
            this.loadSessions();
          }
        },
        error: () => {
          failed++;
          if (completed + failed === active.length) {
            this.notifications.showError(`Extended ${completed} session(s). ${failed} failed.`);
            this.loadSessions();
          }
        }
      });
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

  trackSession(_index: number, session: SessionListItem): string {
    return session.userId;
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}

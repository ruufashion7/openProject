import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService, DashboardSummary } from '../services/api.service';
import { PageStateComponent } from '../shared/page-state/page-state.component';
import { messageFromHttpError } from '../shared/api-error.util';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, PageStateComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css'
})
export class DashboardComponent implements OnInit, OnDestroy {
  summary?: DashboardSummary;
  status: 'loading' | 'idle' | 'failed' = 'loading';
  errorMessage = '';
  autoRefresh = true;
  refreshing = false;
  private initialLoad = true;
  private refreshTimer?: number;

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.load();
    this.scheduleRefresh();
  }

  ngOnDestroy(): void {
    if (this.refreshTimer) {
      window.clearInterval(this.refreshTimer);
    }
  }

  load(): void {
    if (this.initialLoad) {
      this.status = 'loading';
    } else {
      this.refreshing = true;
    }
    this.errorMessage = '';
    this.api.getDashboard().subscribe({
      next: (summary) => {
        this.summary = summary;
        this.status = 'idle';
        this.initialLoad = false;
        this.refreshing = false;
      },
      error: (err: HttpErrorResponse) => {
        this.status = 'failed';
        this.errorMessage = messageFromHttpError(err, 'Failed to load dashboard.');
        this.refreshing = false;
      }
    });
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    this.scheduleRefresh();
  }

  private scheduleRefresh(): void {
    if (this.refreshTimer) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = undefined;
    }
    if (this.autoRefresh) {
      this.refreshTimer = window.setInterval(() => {
        if (this.status !== 'loading') {
          this.api.getDashboard().subscribe({
            next: (summary) => {
              this.summary = summary;
              this.status = 'idle';
            }
          });
        }
      }, 60_000);
    }
  }

  trackUser(_i: number, u: { username: string }): string {
    return u.username;
  }
}

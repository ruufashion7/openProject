import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-page-state',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="loading-state" *ngIf="kind === 'loading'" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true"></div>
      <p>{{ message || 'Loading...' }}</p>
    </div>
    <div class="error-state" *ngIf="kind === 'error'" role="alert">
      <div class="state-icon" aria-hidden="true">⚠️</div>
      <p>{{ message }}</p>
    </div>
    <div class="empty-state" *ngIf="kind === 'empty'">
      <div class="state-icon" aria-hidden="true">📭</div>
      <p>{{ message }}</p>
    </div>
  `
})
export class PageStateComponent {
  @Input() kind: 'loading' | 'error' | 'empty' = 'loading';
  @Input() message = '';
}

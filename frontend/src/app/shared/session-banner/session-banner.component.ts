import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AuthService } from '../../auth/auth.service';

@Component({
  selector: 'app-session-banner',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './session-banner.component.html',
  styleUrl: './session-banner.component.css'
})
export class SessionBannerComponent implements OnInit, OnDestroy {
  remainingSeconds = 0;
  private timerId?: number;

  constructor(private auth: AuthService, private router: Router) {}

  ngOnInit(): void {
    this.refreshRemaining();
    this.timerId = window.setInterval(() => this.refreshRemaining(), 1000);
  }

  ngOnDestroy(): void {
    if (this.timerId) {
      window.clearInterval(this.timerId);
    }
  }

  get remainingLabel(): string {
    const minutes = Math.floor(this.remainingSeconds / 60);
    const seconds = this.remainingSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }

  private refreshRemaining(): void {
    const remaining = this.auth.getRemainingMs();
    if (remaining === null || remaining <= 0) {
      this.auth.logout();
      this.router.navigateByUrl('/login');
      return;
    }
    this.remainingSeconds = Math.ceil(remaining / 1000);
  }
}


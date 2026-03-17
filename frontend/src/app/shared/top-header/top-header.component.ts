import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AuthService } from '../../auth/auth.service';
import { SessionBannerComponent } from '../session-banner/session-banner.component';

@Component({
  selector: 'app-top-header',
  standalone: true,
  imports: [CommonModule, SessionBannerComponent],
  templateUrl: './top-header.component.html',
  styleUrl: './top-header.component.css'
})
export class TopHeaderComponent implements OnInit {
  displayName: string | null = null;
  isMobileMenuOpen = false;

  constructor(
    private auth: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.displayName = this.auth.getDisplayName();
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  toggleMobileMenu(): void {
    this.isMobileMenuOpen = !this.isMobileMenuOpen;
    // Emit event to sidebar or use service for communication
    const event = new CustomEvent('toggleMobileMenu', { detail: { open: this.isMobileMenuOpen } });
    window.dispatchEvent(event);
  }
}


import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { SessionBannerComponent } from '../session-banner/session-banner.component';
import { PageTitleService } from '../page-title.service';
import { SidebarService } from '../sidebar/sidebar.service';

@Component({
  selector: 'app-top-header',
  standalone: true,
  imports: [CommonModule, SessionBannerComponent],
  templateUrl: './top-header.component.html',
  styleUrl: './top-header.component.css'
})
export class TopHeaderComponent implements OnInit, OnDestroy {
  displayName: string | null = null;
  sidebarOpen = true;
  pageTitle = '';
  private destroy$ = new Subject<void>();

  constructor(
    private auth: AuthService,
    private router: Router,
    private pageTitleService: PageTitleService,
    private sidebarService: SidebarService
  ) {}

  ngOnInit(): void {
    this.displayName = this.auth.getDisplayName();
    this.pageTitleService.title$
      .pipe(takeUntil(this.destroy$))
      .subscribe((title) => {
        this.pageTitle = title;
      });
    this.sidebarOpen = this.sidebarService.isOpen();
    this.sidebarService.isOpen$
      .pipe(takeUntil(this.destroy$))
      .subscribe((open) => {
        this.sidebarOpen = open;
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  toggleSidebar(): void {
    this.sidebarService.toggle();
  }
}


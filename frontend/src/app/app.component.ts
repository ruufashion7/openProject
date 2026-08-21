import { Component, OnInit, OnDestroy } from '@angular/core';
import { RouterOutlet, Router, NavigationEnd } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Subject, filter, takeUntil } from 'rxjs';
import { SidebarComponent } from './shared/sidebar/sidebar.component';
import { ScrollButtonComponent } from './shared/scroll-button/scroll-button.component';
import { TopHeaderComponent } from './shared/top-header/top-header.component';
import { NotificationComponent } from './shared/notification/notification.component';
import { GlobalTooltipComponent } from './shared/global-tooltip/global-tooltip.component';
import { SidebarService } from './shared/sidebar/sidebar.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, CommonModule, SidebarComponent, ScrollButtonComponent, TopHeaderComponent, NotificationComponent, GlobalTooltipComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit, OnDestroy {
  showSidebar = false;
  sidebarOpen = true;
  private destroy$ = new Subject<void>();

  constructor(
    private router: Router,
    private sidebarService: SidebarService
  ) {}

  ngOnInit(): void {
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntil(this.destroy$)
      )
      .subscribe((event) => {
        const nav = event as NavigationEnd;
        this.showSidebar = nav.urlAfterRedirects !== '/login' && nav.urlAfterRedirects !== '/';
      });

    this.showSidebar = this.router.url !== '/login' && this.router.url !== '/';
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
}

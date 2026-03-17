import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive, NavigationEnd } from '@angular/router';
import { filter, takeUntil } from 'rxjs/operators';
import { Subject } from 'rxjs';
import { PermissionService } from '../../auth/permission.service';
import { ROUTE_PERMISSIONS } from '../../auth/permissions.config';

interface NavItem {
  label: string;
  route: string;
  icon: string;
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.css'
})
export class SidebarComponent implements OnInit, OnDestroy {
  currentRoute = '';
  navItems: NavItem[] = [];
  isMobileMenuOpen = false;
  private destroy$ = new Subject<void>();
  private mobileMenuHandler?: EventListener;

  // Centralized navigation items - add new routes here
  allNavItems: NavItem[] = [
    { label: 'Welcome', route: '/welcome', icon: '🏠' },
    { label: 'Upload Files', route: '/upload', icon: '📤' },
    { label: 'Rate List', route: '/rate-list', icon: '💵' },
    { label: 'Invoice Details', route: '/sales-details', icon: '📊' },
    { label: 'Sales Analytics', route: '/sales-visualization', icon: '📈' },
    { label: 'Details', route: '/outstanding', icon: '📋' },
    { label: 'Outstanding', route: '/payment-dates', icon: '💰' },
    { label: 'Customer Locations', route: '/customer-locations', icon: '📍' },
    { label: 'Access Control', route: '/access-control', icon: '🔐' }
  ];

  constructor(
    private router: Router,
    private permissionService: PermissionService
  ) {
    // Track current route for active state
    this.router.events
      .pipe(
        filter(event => event instanceof NavigationEnd),
        takeUntil(this.destroy$)
      )
      .subscribe((event: any) => {
        this.currentRoute = event.urlAfterRedirects;
      });
  }

  ngOnInit(): void {
    this.currentRoute = this.router.url;
    this.filterNavItems();
    
    // Listen for mobile menu toggle events - store handler for proper cleanup
    this.mobileMenuHandler = ((event: CustomEvent) => {
      this.isMobileMenuOpen = event.detail.open;
    }) as EventListener;
    window.addEventListener('toggleMobileMenu', this.mobileMenuHandler);
  }

  filterNavItems(): void {
    // Automatically filter based on ROUTE_PERMISSIONS config
    this.navItems = this.allNavItems.filter(item => {
      return this.permissionService.canAccessRoute(item.route);
    });
  }

  private getPathWithoutQuery(url: string): string {
    // Remove query parameters and hash from URL
    // Handle both absolute and relative URLs
    if (url.includes('?')) {
      return url.split('?')[0];
    }
    if (url.includes('#')) {
      return url.split('#')[0];
    }
    return url;
  }

  ngOnDestroy(): void {
    // Cleanup event listener
    if (this.mobileMenuHandler) {
      window.removeEventListener('toggleMobileMenu', this.mobileMenuHandler);
    }
    // Complete destroy subject to cleanup subscriptions
    this.destroy$.next();
    this.destroy$.complete();
  }

  toggleMobileMenu(): void {
    this.isMobileMenuOpen = !this.isMobileMenuOpen;
  }

  closeMobileMenu(): void {
    this.isMobileMenuOpen = false;
  }


  isActive(route: string): boolean {
    // Get path without query parameters
    const currentPath = this.getPathWithoutQuery(this.currentRoute);
    const routePath = this.getPathWithoutQuery(route);
    
    // Exact match or starts with route + '/'
    return currentPath === routePath || currentPath.startsWith(routePath + '/');
  }

  getCurrentPageTitle(): string {
    const routeMap: Record<string, string> = {
      '/welcome': 'Welcome',
      '/upload': 'Upload Files',
      '/rate-list': 'Rate List',
      '/sales-details': 'Invoice Details',
      '/sales-visualization': 'Sales Analytics',
      '/outstanding': 'Details',
      '/payment-dates': 'Outstanding',
      '/customer-locations': 'Customer Locations',
      '/uploads': 'Latest Uploads',
      '/uploads-audit': 'Upload Audit Trail',
      '/uploads-purge': 'Hard Delete Uploads',
      '/dashboard': 'System Dashboard',
      '/access-control': 'Access Control'
    };

    const currentPath = this.getPathWithoutQuery(this.currentRoute);
    for (const [route, title] of Object.entries(routeMap)) {
      const routePath = this.getPathWithoutQuery(route);
      if (currentPath === routePath || currentPath.startsWith(routePath + '/')) {
        return title;
      }
    }
    return '';
  }
}


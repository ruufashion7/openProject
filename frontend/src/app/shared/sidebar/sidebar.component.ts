import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive, NavigationEnd } from '@angular/router';
import { filter, takeUntil } from 'rxjs/operators';
import { Subject } from 'rxjs';
import { PermissionService } from '../../auth/permission.service';
import { PageTitleService } from '../page-title.service';
import { SidebarService } from './sidebar.service';

interface NavChild {
  label: string;
  route: string;
  icon: string;
}

interface NavItem {
  label: string;
  route: string;
  icon: string;
  children?: NavChild[];
}

interface NavSection {
  id: string;
  label: string;
  items: NavItem[];
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
  navSections: NavSection[] = [];
  isOpen = true;
  isMobile = false;
  private destroy$ = new Subject<void>();

  /** Grouped by workflow — add routes in the matching section. */
  private readonly allNavSections: NavSection[] = [
    {
      id: 'home',
      label: 'Home',
      items: [{ label: 'Welcome', route: '/welcome', icon: '🏠' }]
    },
    {
      id: 'data',
      label: 'Data Import',
      items: [
        {
          label: 'Upload Files',
          route: '/upload',
          icon: '📤',
          children: [
            { label: 'Latest Files', route: '/uploads', icon: '📁' },
            { label: 'Audit Trail', route: '/uploads-audit', icon: '📜' },
            { label: 'Hard Delete', route: '/uploads-purge', icon: '🗑️' }
          ]
        }
      ]
    },
    {
      id: 'pricing',
      label: 'Pricing',
      items: [{ label: 'Rate List', route: '/rate-list', icon: '💵' }]
    },
    {
      id: 'customers',
      label: 'Customers & Sales',
      items: [
        { label: 'Outstanding Due', route: '/outstanding-due', icon: '💰' },
        { label: 'Customer Details', route: '/outstanding', icon: '📋' },
        { label: 'Invoice Details', route: '/sales-details', icon: '📊' },
        { label: 'Sales Analytics', route: '/sales-visualization', icon: '📈' },
        { label: 'AI Data Agent', route: '/ai-agent', icon: '🤖' }
      ]
    },
    {
      id: 'outreach',
      label: 'Outreach',
      items: [
        { label: 'WhatsApp', route: '/whatsapp-outreach', icon: '💬' },
        { label: 'Customer Locations', route: '/customer-locations', icon: '📍' }
      ]
    },
    {
      id: 'admin',
      label: 'Administration',
      items: [
        { label: 'Operations Overview', route: '/dashboard', icon: '🖥️' },
        { label: 'Sessions', route: '/sessions', icon: '👥' },
        { label: 'Access Control', route: '/access-control', icon: '🔐' }
      ]
    }
  ];

  constructor(
    private router: Router,
    private permissionService: PermissionService,
    private pageTitleService: PageTitleService,
    private sidebarService: SidebarService
  ) {
    this.router.events
      .pipe(
        filter(event => event instanceof NavigationEnd),
        takeUntil(this.destroy$)
      )
      .subscribe((event: any) => {
        this.currentRoute = event.urlAfterRedirects;
        this.pageTitleService.setFromUrl(this.currentRoute);
      });
  }

  ngOnInit(): void {
    this.currentRoute = this.router.url;
    this.pageTitleService.setFromUrl(this.currentRoute);
    this.buildNavSections();
    this.isMobile = this.sidebarService.isMobile();
    this.isOpen = this.sidebarService.isOpen();

    this.sidebarService.isOpen$
      .pipe(takeUntil(this.destroy$))
      .subscribe((open) => {
        this.isOpen = open;
      });

    window.addEventListener('resize', this.syncMobile);
  }

  private syncMobile = (): void => {
    this.isMobile = this.sidebarService.isMobile();
  };

  private filterItem(item: NavItem): NavItem | null {
    const children = item.children?.filter((child) =>
      this.permissionService.canAccessRoute(child.route)
    );
    const canParent = this.permissionService.canAccessRoute(item.route);

    if (item.children?.length) {
      if (!canParent && (!children || children.length === 0)) {
        return null;
      }
      return { ...item, children: children ?? [] };
    }
    return canParent ? item : null;
  }

  buildNavSections(): void {
    this.navSections = this.allNavSections
      .map((section) => {
        const items = section.items
          .map((item) => this.filterItem(item))
          .filter((item): item is NavItem => item !== null);
        return items.length ? { ...section, items } : null;
      })
      .filter((section): section is NavSection => section !== null);
  }

  hasVisibleChildren(item: NavItem): boolean {
    return !!item.children && item.children.length > 0;
  }

  isParentActive(item: NavItem): boolean {
    if (!this.hasVisibleChildren(item)) {
      return this.isActive(item.route);
    }
    const childActive = item.children!.some((child) => this.isActive(child.route));
    return this.isActive(item.route) && !childActive;
  }

  isGroupActive(item: NavItem): boolean {
    if (!this.hasVisibleChildren(item)) {
      return false;
    }
    return (
      this.isActive(item.route) ||
      item.children!.some((child) => this.isActive(child.route))
    );
  }

  private getPathWithoutQuery(url: string): string {
    if (url.includes('?')) {
      return url.split('?')[0];
    }
    if (url.includes('#')) {
      return url.split('#')[0];
    }
    return url;
  }

  ngOnDestroy(): void {
    window.removeEventListener('resize', this.syncMobile);
    this.destroy$.next();
    this.destroy$.complete();
  }

  closeSidebar(): void {
    this.sidebarService.close();
  }

  isActive(route: string): boolean {
    const currentPath = this.getPathWithoutQuery(this.currentRoute);
    const routePath = this.getPathWithoutQuery(route);
    return currentPath === routePath || currentPath.startsWith(routePath + '/');
  }
}

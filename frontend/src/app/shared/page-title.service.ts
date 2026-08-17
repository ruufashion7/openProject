import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

const ROUTE_TITLES: Record<string, string> = {
  '/welcome': 'Welcome',
  '/upload': 'Upload Files',
  '/rate-list': 'Rate List',
  '/sales-details': 'Invoice Details',
  '/sales-visualization': 'Sales Analytics',
  '/outstanding': 'Customer Details',
  '/outstanding-due': 'Outstanding Due',
  '/ai-agent': 'AI Data Agent',
  '/bill-extract': 'Bill Reader',
  '/whatsapp-outreach': 'WhatsApp Outreach',
  '/customer-locations': 'Customer Locations',
  '/uploads': 'Latest Files',
  '/uploads-audit': 'Audit Trail',
  '/uploads-purge': 'Hard Delete Uploads',
  '/dashboard': 'Operations Overview',
  '/sessions': 'Sessions',
  '/access-control': 'Access Control'
};

@Injectable({ providedIn: 'root' })
export class PageTitleService {
  private readonly titleSubject = new BehaviorSubject<string>('');
  readonly title$ = this.titleSubject.asObservable();

  resolveFromUrl(url: string): string {
    const path = url.split('?')[0].split('#')[0];
    for (const [route, title] of Object.entries(ROUTE_TITLES)) {
      if (path === route || path.startsWith(route + '/')) {
        return title;
      }
    }
    return '';
  }

  setFromUrl(url: string): void {
    this.titleSubject.next(this.resolveFromUrl(url));
  }
}

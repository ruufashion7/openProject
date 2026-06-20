import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

const STORAGE_KEY = 'sidebar-open';
const DESKTOP_BREAKPOINT = 768;

@Injectable({ providedIn: 'root' })
export class SidebarService {
  private readonly isOpenSubject = new BehaviorSubject<boolean>(this.readInitialOpen());
  readonly isOpen$ = this.isOpenSubject.asObservable();

  constructor() {
    window.addEventListener('resize', this.onResize);
  }

  isOpen(): boolean {
    return this.isOpenSubject.value;
  }

  isMobile(): boolean {
    return window.innerWidth <= DESKTOP_BREAKPOINT;
  }

  toggle(): void {
    this.setOpen(!this.isOpenSubject.value);
  }

  open(): void {
    this.setOpen(true);
  }

  close(): void {
    this.setOpen(false);
  }

  private setOpen(open: boolean): void {
    this.isOpenSubject.next(open);
    if (!this.isMobile()) {
      localStorage.setItem(STORAGE_KEY, String(open));
    }
  }

  private readInitialOpen(): boolean {
    if (window.innerWidth <= DESKTOP_BREAKPOINT) {
      return false;
    }
    const saved = localStorage.getItem(STORAGE_KEY);
    return saved === null ? true : saved === 'true';
  }

  private readStoredOpen(): boolean {
    const saved = localStorage.getItem(STORAGE_KEY);
    return saved === null ? true : saved === 'true';
  }

  private onResize = (): void => {
    const mobile = this.isMobile();
    if (mobile) {
      this.isOpenSubject.next(false);
      return;
    }
    this.isOpenSubject.next(this.readStoredOpen());
  };
}

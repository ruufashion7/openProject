import { Component, HostListener, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { closestTooltipHost, tooltipTextFor } from './tooltip.util';

@Component({
  selector: 'app-global-tooltip',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './global-tooltip.component.html',
  styleUrl: './global-tooltip.component.css'
})
export class GlobalTooltipComponent implements OnDestroy {
  text = '';
  x = 0;
  y = 0;
  placement: 'above' | 'below' = 'above';
  align: 'center' | 'start' | 'end' = 'center';

  private anchor: HTMLElement | null = null;
  private showTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly showDelayMs = 280;

  @HostListener('document:pointerover', ['$event'])
  onPointerOver(event: PointerEvent): void {
    if (event.pointerType === 'touch') {
      return;
    }
    const host = closestTooltipHost(event.target);
    if (host === this.anchor) {
      return;
    }
    this.clearTimer();
    this.restoreTitle(this.anchor);
    this.anchor = null;
    this.text = '';
    if (!host) {
      return;
    }
    const next = tooltipTextFor(host);
    if (!next) {
      return;
    }
    this.anchor = host;
    this.showTimer = setTimeout(() => this.show(host, next), this.showDelayMs);
  }

  @HostListener('document:pointerout', ['$event'])
  onPointerOut(event: PointerEvent): void {
    if (!this.anchor) {
      return;
    }
    const next = closestTooltipHost(event.relatedTarget);
    if (next === this.anchor) {
      return;
    }
    this.hide();
  }

  @HostListener('document:focusin', ['$event'])
  onFocusIn(event: FocusEvent): void {
    const host = closestTooltipHost(event.target);
    if (!host || host === this.anchor) {
      return;
    }
    const next = tooltipTextFor(host);
    if (!next) {
      return;
    }
    this.clearTimer();
    this.restoreTitle(this.anchor);
    this.anchor = host;
    this.show(host, next);
  }

  @HostListener('document:focusout', ['$event'])
  onFocusOut(event: FocusEvent): void {
    if (!this.anchor) {
      return;
    }
    const next = closestTooltipHost(event.relatedTarget);
    if (next === this.anchor) {
      return;
    }
    this.hide();
  }

  @HostListener('document:keydown.escape')
  @HostListener('window:scroll')
  @HostListener('window:resize')
  @HostListener('document:pointerdown')
  onDismiss(): void {
    this.hide();
  }

  ngOnDestroy(): void {
    this.hide();
  }

  private show(host: HTMLElement, text: string): void {
    this.stashTitle(host);
    const rect = host.getBoundingClientRect();
    this.text = text;
    this.x = rect.left + rect.width / 2;
    this.y = rect.top;
    this.placement = rect.top < 48 ? 'below' : 'above';
    if (this.placement === 'below') {
      this.y = rect.bottom;
    }
    const edge = 120;
    if (this.x < edge) {
      this.align = 'start';
      this.x = Math.max(8, rect.left);
    } else if (this.x > window.innerWidth - edge) {
      this.align = 'end';
      this.x = Math.min(window.innerWidth - 8, rect.right);
    } else {
      this.align = 'center';
    }
  }

  private hide(): void {
    this.clearTimer();
    this.restoreTitle(this.anchor);
    this.anchor = null;
    this.text = '';
  }

  private stashTitle(el: HTMLElement): void {
    const title = el.getAttribute('title');
    if (title == null || title === '') {
      return;
    }
    if (!el.hasAttribute('data-tooltip-native')) {
      el.setAttribute('data-tooltip-native', title);
    }
    el.removeAttribute('title');
  }

  private restoreTitle(el: HTMLElement | null): void {
    if (!el) {
      return;
    }
    const stored = el.getAttribute('data-tooltip-native');
    if (stored != null) {
      el.setAttribute('title', stored);
      el.removeAttribute('data-tooltip-native');
    }
  }

  private clearTimer(): void {
    if (this.showTimer != null) {
      clearTimeout(this.showTimer);
      this.showTimer = null;
    }
  }
}

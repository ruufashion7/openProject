export const TOOLTIP_HOST_SELECTOR = '[data-tooltip], [title], [aria-label]';

export function closestTooltipHost(target: EventTarget | null): HTMLElement | null {
  if (!(target instanceof Element)) {
    return null;
  }
  const host = target.closest(TOOLTIP_HOST_SELECTOR);
  if (!(host instanceof HTMLElement) || host.closest('.global-tooltip')) {
    return null;
  }
  return host;
}

export function tooltipTextFor(el: HTMLElement): string {
  const data = el.getAttribute('data-tooltip')?.trim();
  if (data) {
    return skipIfRedundant(el, data);
  }
  const title = el.getAttribute('title')?.trim()
      || el.getAttribute('data-tooltip-native')?.trim();
  if (title) {
    return skipIfRedundant(el, title);
  }
  const aria = el.getAttribute('aria-label')?.trim();
  if (aria && isCompactControl(el)) {
    return skipIfRedundant(el, aria);
  }
  return '';
}

export function visibleTooltipText(el: HTMLElement): string {
  return (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
}

export function isTruncated(el: HTMLElement): boolean {
  return el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1;
}

function isCompactControl(el: HTMLElement): boolean {
  const tag = el.tagName;
  if (tag !== 'BUTTON' && tag !== 'A' && tag !== 'SUMMARY') {
    return false;
  }
  return visibleTooltipText(el).length <= 2;
}

function skipIfRedundant(el: HTMLElement, text: string): string {
  const visible = visibleTooltipText(el);
  if (visible.length >= 4 && visible.toLowerCase() === text.toLowerCase() && !isTruncated(el)) {
    return '';
  }
  return text;
}

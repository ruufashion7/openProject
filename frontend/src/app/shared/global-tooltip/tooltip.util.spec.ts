import { tooltipTextFor } from './tooltip.util';

describe('tooltipTextFor', () => {
  it('uses title on icon buttons', () => {
    const button = document.createElement('button');
    button.setAttribute('title', 'Download Excel');
    button.textContent = '📥';
    expect(tooltipTextFor(button)).toBe('Download Excel');
  });

  it('skips title that repeats visible label', () => {
    const button = document.createElement('button');
    button.setAttribute('title', 'Logout');
    button.textContent = 'Logout';
    expect(tooltipTextFor(button)).toBe('');
  });

  it('uses aria-label on compact icon-only controls', () => {
    const button = document.createElement('button');
    button.setAttribute('aria-label', 'Close notification');
    button.textContent = '×';
    expect(tooltipTextFor(button)).toBe('Close notification');
  });

  it('prefers data-tooltip over title', () => {
    const button = document.createElement('button');
    button.setAttribute('title', 'Old');
    button.setAttribute('data-tooltip', 'Due dates and notes from Google Drive');
    button.textContent = '📑';
    expect(tooltipTextFor(button)).toBe('Due dates and notes from Google Drive');
  });
});

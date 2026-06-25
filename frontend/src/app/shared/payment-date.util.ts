export type PaymentDateTone = 'neutral' | 'yellow' | 'green' | 'red';
export type PaymentDateFilterMode = 'all' | 'past' | 'today' | 'future' | 'none';

export const PAYMENT_DATE_SAVE_DEBOUNCE_MS = 400;

export function normalizeToDayMonth(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) {
    return '';
  }
  if (/^\d{2}-\d{2}$/.test(trimmed)) {
    return trimmed;
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(trimmed)) {
    const parts = trimmed.split('-');
    return `${parts[2]}-${parts[1]}`;
  }
  return null;
}

export function toIsoDate(value: string): string | null {
  const trimmed = value.trim();
  if (!/^\d{2}-\d{2}$/.test(trimmed)) {
    return null;
  }
  const [day, month] = trimmed.split('-');
  const year = new Date().getFullYear().toString();
  return `${year}-${month}-${day}`;
}

export function getPaymentDateTone(date: string | null | undefined): PaymentDateTone {
  if (!date) {
    return 'neutral';
  }
  const value = date.trim();
  if (!value) {
    return 'neutral';
  }
  const match = value.match(/^(\d{2})-(\d{2})$/);
  if (!match) {
    return 'neutral';
  }
  const day = Number(match[1]);
  const month = Number(match[2]);
  if (!day || !month || day > 31 || month > 12) {
    return 'neutral';
  }
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const target = new Date(now.getFullYear(), month - 1, day);
  if (Number.isNaN(target.getTime())) {
    return 'neutral';
  }
  const todayTime = today.getTime();
  const targetTime = target.getTime();
  if (targetTime === todayTime) {
    return 'yellow';
  }
  if (targetTime > todayTime) {
    return 'green';
  }
  return 'red';
}

export function getPaymentDateBorderClass(tone: PaymentDateTone): string {
  switch (tone) {
    case 'red':
      return 'border-red';
    case 'yellow':
      return 'border-yellow';
    case 'green':
      return 'border-green';
    case 'neutral':
    default:
      return 'border-grey';
  }
}

export function matchesPaymentDateFilter(
  date: string | null | undefined,
  mode: PaymentDateFilterMode
): boolean {
  if (mode === 'all') {
    return true;
  }
  if (!date || date.trim() === '') {
    return mode === 'none';
  }
  const tone = getPaymentDateTone(date);
  if (mode === 'past') {
    return tone === 'red';
  }
  if (mode === 'today') {
    return tone === 'yellow';
  }
  if (mode === 'future') {
    return tone === 'green';
  }
  return false;
}

export function isValidPaymentDateFormat(value: string): boolean {
  const cleaned = value.trim();
  return !cleaned || /^\d{2}-\d{2}$/.test(cleaned);
}

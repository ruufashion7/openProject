/** Strip non-digits and leading 91 / 0 so Indian mobiles display as 10 digits. */
export function normalizePhoneDigits(raw: string | null | undefined): string {
  let digits = (raw ?? '').replace(/\D/g, '');
  if (digits.length === 12 && digits.startsWith('91')) {
    digits = digits.slice(2);
  } else if (digits.length === 11 && digits.startsWith('0')) {
    digits = digits.slice(1);
  }
  return digits;
}

export function formatPhoneDisplay(raw: string | null | undefined): string {
  return normalizePhoneDigits(raw);
}

export function phoneDigitsMatch(stored: string | null | undefined, queryDigits: string): boolean {
  const phone = normalizePhoneDigits(stored);
  const query = normalizePhoneDigits(queryDigits);
  if (!phone || !query) {
    return false;
  }
  return phone.includes(query) || query.includes(phone);
}

export function formatPhoneForTel(raw: string | null | undefined): string {
  const digits = normalizePhoneDigits(raw);
  if (digits.length === 10) {
    return `+91${digits}`;
  }
  return digits ? `+${digits}` : '';
}

export function formatPhoneForWhatsApp(raw: string | null | undefined): string {
  const digits = normalizePhoneDigits(raw);
  if (digits.length === 10) {
    return `91${digits}`;
  }
  return digits;
}

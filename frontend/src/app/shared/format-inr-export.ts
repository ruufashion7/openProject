/** Indian-style integer grouping: 5295477 → 52,95,477 */
export function formatIndianGroupedIntegerPart(intStr: string): string {
  const neg = intStr.startsWith('-');
  const d = neg ? intStr.slice(1) : intStr;
  if (!d) {
    return intStr;
  }
  const last3 = d.slice(-3);
  const rest = d.slice(0, -3);
  if (!rest) {
    return (neg ? '-' : '') + last3;
  }
  const grouped = rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',');
  return (neg ? '-' : '') + grouped + ',' + last3;
}

/** Excel / UI: full ₹ symbol (supported by Excel). */
export function formatInrForExcel(amount: number): string {
  const n = Number(amount);
  if (!Number.isFinite(n)) {
    return '';
  }
  const [intPart, frac = '00'] = n.toFixed(2).split('.');
  return `₹${formatIndianGroupedIntegerPart(intPart)}.${frac}`;
}

/**
 * Same as {@link formatInrForExcel}. Use only after {@link ensurePdfUnicodeFonts}
 * registers Noto Sans on the jsPDF instance.
 */
export function formatInrForPdf(amount: number): string {
  return formatInrForExcel(amount);
}

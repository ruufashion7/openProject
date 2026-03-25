import jsPDF from 'jspdf';

// Noto Sans TTFs in assets/fonts — OFL 1.1 (googlefonts/noto-fonts); includes ₹ (U+20B9).

/** jsPDF family name (registered from Noto Sans TTF under assets). */
export const PDF_UNICODE_FONT = 'NotoSansPdf';

const VFS_REGULAR = 'NotoSans-Regular.ttf';
const VFS_BOLD = 'NotoSans-Bold.ttf';

let cachedRegularBase64: string | null = null;
let cachedBoldBase64: string | null = null;

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    const sub = bytes.subarray(i, i + chunk);
    binary += String.fromCharCode.apply(null, sub as unknown as number[]);
  }
  return btoa(binary);
}

function resolveFontUrls(): { regular: string; bold: string } {
  const base =
    typeof document !== 'undefined' && document.baseURI
      ? document.baseURI
      : typeof window !== 'undefined'
        ? `${window.location.origin}/`
        : '/';
  return {
    regular: new URL('assets/fonts/NotoSans-Regular.ttf', base).href,
    bold: new URL('assets/fonts/NotoSans-Bold.ttf', base).href,
  };
}

/**
 * Loads Noto Sans into this jsPDF instance so ₹ and other Unicode text render correctly.
 * Font bytes are fetched once and cached in memory.
 */
export async function ensurePdfUnicodeFonts(doc: jsPDF): Promise<void> {
  if ((doc as unknown as { __unicodeFonts?: boolean }).__unicodeFonts) {
    doc.setFont(PDF_UNICODE_FONT, 'normal');
    return;
  }
  if (!cachedRegularBase64 || !cachedBoldBase64) {
    const { regular, bold } = resolveFontUrls();
    const [regRes, boldRes] = await Promise.all([fetch(regular), fetch(bold)]);
    if (!regRes.ok) {
      throw new Error(`Failed to load PDF font (${regRes.status}): ${regular}`);
    }
    if (!boldRes.ok) {
      throw new Error(`Failed to load PDF font (${boldRes.status}): ${bold}`);
    }
    const [regBuf, boldBuf] = await Promise.all([regRes.arrayBuffer(), boldRes.arrayBuffer()]);
    cachedRegularBase64 = arrayBufferToBase64(regBuf);
    cachedBoldBase64 = arrayBufferToBase64(boldBuf);
  }
  doc.addFileToVFS(VFS_REGULAR, cachedRegularBase64);
  doc.addFont(VFS_REGULAR, PDF_UNICODE_FONT, 'normal');
  doc.addFileToVFS(VFS_BOLD, cachedBoldBase64);
  doc.addFont(VFS_BOLD, PDF_UNICODE_FONT, 'bold');
  (doc as unknown as { __unicodeFonts?: boolean }).__unicodeFonts = true;
  doc.setFont(PDF_UNICODE_FONT, 'normal');
}

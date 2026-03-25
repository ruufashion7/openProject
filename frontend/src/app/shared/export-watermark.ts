import jsPDF from 'jspdf';
import * as XLSX from 'xlsx';

/** Brand text applied to exported PDF/Excel downloads */
export const RUU_FASHION_WATERMARK = 'RUU FASHION';

/**
 * Large diagonal watermark coverage on the current PDF page.
 * Call from jspdf-autotable {@code didDrawPage} so every page is stamped.
 */
export function addRuufashionPdfWatermark(doc: jsPDF): void {
  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  const cx = pageWidth / 2;
  const cy = pageHeight / 2;

  doc.saveGraphicsState();
  doc.setTextColor(210, 210, 210);
  doc.setFont('helvetica', 'bold');
  doc.setGState(doc.GState({ opacity: 0.13 }));

  doc.setFontSize(54);
  doc.text(RUU_FASHION_WATERMARK, cx, cy, { angle: 32, align: 'center' });

  doc.setFontSize(26);
  const positions: [number, number][] = [
    [0.18, 0.22],
    [0.5, 0.14],
    [0.82, 0.22],
    [0.18, 0.78],
    [0.5, 0.86],
    [0.82, 0.78],
    [0.08, 0.5],
    [0.92, 0.5],
  ];
  for (const [fx, fy] of positions) {
    doc.text(RUU_FASHION_WATERMARK, pageWidth * fx, pageHeight * fy, {
      angle: 32,
      align: 'center',
    });
  }

  doc.restoreGraphicsState();
}

/** Same as {@link addRuufashionPdfWatermark}; kept for call sites that use this name. */
export const addWatermark = addRuufashionPdfWatermark;

/**
 * Build a merged top row for SheetJS exports and return row index (0) for {@code !merges}.
 */
export function buildExcelWatermarkRow(totalCols: number): unknown[] {
  const row: unknown[] = new Array(totalCols).fill('');
  row[Math.floor(totalCols / 2)] = RUU_FASHION_WATERMARK;
  return row;
}

/**
 * Hint Excel to repeat row 1 (0-based row 0) at the top of each printed page.
 * Safe no-op if the runtime SheetJS build ignores {@code Workbook.Names}.
 */
export function setExcelPrintTitleTopRow(wb: XLSX.WorkBook, sheetName: string): void {
  const safe = sheetName.replace(/'/g, "''");
  if (!wb.Workbook) {
    wb.Workbook = {};
  }
  if (!wb.Workbook.Names) {
    wb.Workbook.Names = [];
  }
  wb.Workbook.Names.push({
    Name: '_xlnm.Print_Titles',
    Ref: `'${safe}'!$1:$1`,
  });
}

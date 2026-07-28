package org.example.ai;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class AiAgentPdfService {

    public byte[] buildTablePdf(String title, List<String> columns, List<List<String>> rows) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.DARK_GRAY);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
            Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);

            document.add(new Paragraph(title == null || title.isBlank() ? "AI Agent Export" : title, titleFont));
            document.add(new Paragraph("Generated " + LocalDate.now() + " · Ruufashion", metaFont));
            document.add(new Paragraph(" ", cellFont));

            int cols = Math.max(1, columns == null ? 1 : columns.size());
            PdfPTable table = new PdfPTable(cols);
            table.setWidthPercentage(100);

            if (columns != null) {
                for (String col : columns) {
                    PdfPCell cell = new PdfPCell(new Phrase(nullToEmpty(col), headerFont));
                    cell.setBackgroundColor(new Color(37, 99, 235));
                    cell.setPadding(5);
                    cell.setHorizontalAlignment(Element.ALIGN_LEFT);
                    table.addCell(cell);
                }
            }

            if (rows != null) {
                for (List<String> row : rows) {
                    for (int i = 0; i < cols; i++) {
                        String value = row != null && i < row.size() ? nullToEmpty(row.get(i)) : "";
                        PdfPCell cell = new PdfPCell(new Phrase(value, cellFont));
                        cell.setPadding(4);
                        table.addCell(cell);
                    }
                }
            }

            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to build PDF", e);
        }
    }

    @SuppressWarnings("unchecked")
    public byte[] buildFromAttachment(Map<String, Object> attachment) {
        String title = stringVal(attachment.get("title"), "AI Agent Export");
        List<String> columns = (List<String>) attachment.get("columns");
        List<List<String>> rows = (List<List<String>>) attachment.get("rows");
        return buildTablePdf(title, columns, rows);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String stringVal(Object o, String fallback) {
        if (o == null) {
            return fallback;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? fallback : s;
    }
}

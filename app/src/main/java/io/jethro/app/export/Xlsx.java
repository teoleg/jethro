package io.jethro.app.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal, dependency-free XLSX (OOXML SpreadsheetML) writer — an .xlsx is a zip of XML parts,
 * so a diagnostics export needs no Apache POI on the classpath. Numbers are written as numeric
 * cells (so Excel sums/sorts them); everything else as inline strings (no shared-strings table to
 * maintain). Not a general spreadsheet engine — no styles, formulas or dates — just clean tabular
 * dumps for the strategy post-mortem export (each sheet is a header row plus data rows).
 */
public final class Xlsx {

    /** One named sheet: a header row followed by data rows; cells are String or Number. */
    public record Sheet(String name, List<String> headers, List<List<Object>> rows) {
    }

    private final List<Sheet> sheets = new ArrayList<>();

    public Xlsx sheet(String name, List<String> headers, List<List<Object>> rows) {
        sheets.add(new Sheet(safeName(name, sheets.size()), headers, rows));
        return this;
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                put(zip, "[Content_Types].xml", contentTypes());
                put(zip, "_rels/.rels", rootRels());
                put(zip, "xl/workbook.xml", workbook());
                put(zip, "xl/_rels/workbook.xml.rels", workbookRels());
                for (int i = 0; i < sheets.size(); i++) {
                    put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
                }
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void put(ZipOutputStream zip, String path, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String contentTypes() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private String workbook() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ");
        sb.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"").append(xml(sheets.get(i).name())).append("\" sheetId=\"")
                    .append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return sb.append("</sheets></workbook>").toString();
    }

    private String workbookRels() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<Relationship Id=\"rId").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i + 1).append(".xml\"/>");
        }
        return sb.append("</Relationships>").toString();
    }

    private static String sheetXml(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        int r = 1;
        sb.append("<row r=\"").append(r++).append("\">");
        for (int c = 0; c < sheet.headers().size(); c++) {
            inlineStr(sb, cellRef(c, 1), sheet.headers().get(c));
        }
        sb.append("</row>");
        for (List<Object> row : sheet.rows()) {
            sb.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < row.size(); c++) {
                Object v = row.get(c);
                String ref = cellRef(c, r);
                if (v instanceof Number n) {
                    String num = v instanceof java.math.BigDecimal bd ? bd.toPlainString() : n.toString();
                    sb.append("<c r=\"").append(ref).append("\"><v>").append(num).append("</v></c>");
                } else {
                    inlineStr(sb, ref, v == null ? "" : v.toString());
                }
            }
            sb.append("</row>");
            r++;
        }
        return sb.append("</sheetData></worksheet>").toString();
    }

    private static void inlineStr(StringBuilder sb, String ref, String value) {
        sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(xml(value)).append("</t></is></c>");
    }

    /** A1-style cell reference for a zero-based column and one-based row. */
    private static String cellRef(int col, int row) {
        StringBuilder col1 = new StringBuilder();
        int c = col;
        do {
            col1.insert(0, (char) ('A' + c % 26));
            c = c / 26 - 1;
        } while (c >= 0);
        return col1.append(row).toString();
    }

    private static String xml(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(ch < 0x20 && ch != '\t' && ch != '\n' ? ' ' : ch);
            }
        }
        return out.toString();
    }

    /** Excel sheet names: ≤31 chars, none of []:*?/\, and unique. */
    private static String safeName(String name, int index) {
        String clean = (name == null ? "Sheet" : name).replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (clean.isEmpty()) {
            clean = "Sheet" + (index + 1);
        }
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }
}

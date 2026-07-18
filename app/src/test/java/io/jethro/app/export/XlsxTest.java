package io.jethro.app.export;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dependency-free XLSX writer produces a well-formed OOXML package (valid zip, required
 *  parts present, numbers as numeric cells, XML-special values escaped, sheet names sanitised). */
class XlsxTest {

    @Test
    void producesAValidMultiSheetPackage() throws Exception {
        byte[] bytes = new Xlsx()
                .sheet("P&L by book", List.of("book", "pnl"),
                        List.of(List.of("MACRO", new BigDecimal("-1231.71")),
                                List.of("ALPHA", new BigDecimal("42.00"))))
                .sheet("Positions", List.of("id"), List.of(List.of("A<B>&\"C")))
                .toBytes();

        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                parts.put(e.getName(), new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertTrue(parts.containsKey("[Content_Types].xml"));
        assertTrue(parts.containsKey("_rels/.rels"));
        assertTrue(parts.containsKey("xl/workbook.xml"));
        assertTrue(parts.containsKey("xl/_rels/workbook.xml.rels"));
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"));
        assertTrue(parts.containsKey("xl/worksheets/sheet2.xml"));

        // Numbers are numeric cells (no t="inlineStr"), and plain (not scientific).
        String s1 = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(s1.contains("<v>-1231.71</v>"), s1);
        // XML-special characters are escaped in string cells.
        String s2 = parts.get("xl/worksheets/sheet2.xml");
        assertTrue(s2.contains("A&lt;B&gt;&amp;&quot;C"), s2);
        // Sheet name with & is kept but valid in the workbook part.
        assertTrue(parts.get("xl/workbook.xml").contains("P&amp;L by book"));
    }

    @Test
    void longAndIllegalSheetNamesAreSanitised() throws Exception {
        byte[] bytes = new Xlsx()
                .sheet("this:name/is*way?too[long]for-excel-to-accept-ok", List.of("h"),
                        List.of(List.of("x")))
                .toBytes();
        String wbXml = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                if (e.getName().equals("xl/workbook.xml")) {
                    wbXml = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        // Extract the sheet name attribute; ≤31 chars and no []:*?/\ survive.
        int i = wbXml.indexOf("name=\"") + 6;
        String name = wbXml.substring(i, wbXml.indexOf('"', i));
        assertTrue(name.length() <= 31, "sheet name length " + name.length() + ": " + name);
        assertEquals(-1, name.indexOf(':'));
        assertEquals(-1, name.indexOf('*'));
        assertEquals(-1, name.indexOf('/'));
    }
}

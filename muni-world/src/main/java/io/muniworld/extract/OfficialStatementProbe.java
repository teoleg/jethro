package io.muniworld.extract;

import io.muniworld.pdf.OcrText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diagnostic for an OS PDF that yields no bonds: reports the EVIDENCE for why, instead of leaving a silent
 * "0 indexed". A real Official Statement can fail to parse for three quite different reasons and the fix
 * differs for each, so the probe separates them:
 *
 * <ul>
 *   <li><b>Image-only (scanned) PDF</b> — pages exist but there is almost no extractable text. Needs OCR
 *       ({@code muni.ocr.enabled=true} + tesseract). {@code sparse} says so.</li>
 *   <li><b>Text present, no CUSIPs found</b> — the schedule is there but its identifiers are not in a shape
 *       the parser recognises (e.g. suffix-only columns under a base CUSIP the document states in wording
 *       {@code BASE_CUSIP} does not match).</li>
 *   <li><b>CUSIPs found but rows incomplete</b> — table geometry: PDFBox emits reading order, not columns,
 *       so a row's coupon/maturity can land on different lines than its CUSIP. These are the quarantined
 *       lines; {@code cusipLines} shows them verbatim.</li>
 * </ul>
 *
 * Read-only and side-effect free: it indexes nothing and stores nothing. Every number is measured from the
 * document (invariant 7 — nothing here is inferred or invented).
 */
public final class OfficialStatementProbe {

    private static final Pattern FULL_CUSIP = Pattern.compile("\\b([0-9]{3}[0-9A-Z]{5}[0-9])\\b");
    private static final Pattern SCHEDULE_HEADING = Pattern.compile(
            "(?i)(maturity schedule|maturities|due\\s+[A-Z][a-z]+\\s+\\d{1,2})");

    private OfficialStatementProbe() {
    }

    /** Diagnose {@code text} extracted from a {@code pages}-page OS. Pure — no I/O, no model. */
    public static Map<String, Object> probe(String text, int pages, boolean ocrEnabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        String t = text == null ? "" : text;
        String[] lines = t.split("\\r?\\n");

        out.put("pages", pages);
        out.put("textChars", t.length());
        out.put("charsPerPage", pages > 0 ? t.length() / pages : 0);
        out.put("lines", lines.length);

        boolean sparse = OcrText.isSparse(t, pages);
        out.put("sparse", sparse);
        out.put("ocrEnabled", ocrEnabled);

        List<String> cusips = new ArrayList<>();
        Matcher m = FULL_CUSIP.matcher(t);
        while (m.find() && cusips.size() < 25) {
            if (!cusips.contains(m.group(1))) {
                cusips.add(m.group(1));
            }
        }
        out.put("fullCusipsFound", cusips.size());
        out.put("fullCusipSample", cusips);

        // The verbatim lines a CUSIP appears on — this is what the parser sees, and the fastest way to tell
        // a split-column layout (CUSIP alone on its line) from a complete row it simply mis-reads.
        List<String> cusipLines = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (!line.isEmpty() && FULL_CUSIP.matcher(line).find() && cusipLines.size() < 15) {
                cusipLines.add(truncate(line));
            }
        }
        out.put("cusipLines", cusipLines);

        out.put("hasScheduleHeading", SCHEDULE_HEADING.matcher(t).find());

        // How the document WRITES its identifiers — the decisive evidence when full CUSIP-9s are absent.
        // A real OS commonly prints a base CUSIP-6 once and then only 2–3 char suffixes per maturity row;
        // whether the parser can assemble those depends on it recognising the base, so report both the
        // detected base and the raw wording around every "CUSIP" mention.
        out.put("baseCusipDetected", OfficialStatementParser.findBaseCusip(t, null));
        int[] md = OfficialStatementParser.findScheduleMonthDay(t);
        out.put("scheduleMonthDay", md == null ? null : md[0] + "/" + md[1]);

        List<String> mentions = new ArrayList<>();
        Matcher cm = Pattern.compile("(?i)cusip").matcher(t);
        while (cm.find() && mentions.size() < 12) {
            int s = Math.max(0, cm.start() - 60);
            int e = Math.min(t.length(), cm.end() + 90);
            mentions.add(t.substring(s, e).replaceAll("\\s+", " ").strip());
        }
        out.put("cusipMentions", mentions);

        // The schedule block itself: the lines following the first maturity-schedule heading. This is the
        // table the parser must read, verbatim and in the order PDFBox emits it.
        List<String> block = new ArrayList<>();
        Matcher sh = SCHEDULE_HEADING.matcher(t);
        if (sh.find()) {
            String[] after = t.substring(sh.start()).split("\\r?\\n");
            for (String raw : after) {
                String line = raw.strip();
                if (!line.isEmpty() && block.size() < 45) {
                    block.add(truncate(line));
                }
            }
        }
        out.put("scheduleBlock", block);

        // What the parser actually managed, on this exact text — so the probe and the loader never disagree.
        OfficialStatementParser.Result res = OfficialStatementParser.parse(t, null, null, "");
        out.put("parsedRows", res.rows().size());
        out.put("quarantined", res.quarantined());
        out.put("confidence", res.confidence());

        List<String> head = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (!line.isEmpty() && head.size() < 30) {
                head.add(truncate(line));
            }
        }
        out.put("firstLines", head);

        out.put("diagnosis", diagnose(sparse, ocrEnabled, pages, cusips.size(), res,
                (String) out.get("baseCusipDetected")));
        return out;
    }

    private static String diagnose(boolean sparse, boolean ocrEnabled, int pages, int cusips,
                                   OfficialStatementParser.Result res, String baseCusip) {
        if (pages == 0) {
            return "PDF has no pages — not a readable PDF.";
        }
        // Order matters: evidence of a working text layer (a real CUSIP token) OUTRANKS the sparse
        // heuristic, which is only a <100-chars-per-page rule of thumb. Calling a document "scanned, no
        // text layer" while quoting a CUSIP read out of that very text is a contradiction the reader
        // would (rightly) not trust.
        // Several CUSIPs but no completed rows = the identifiers are there and the failure is geometry.
        // (Checked before `sparse`: evidence of a working text layer outranks the <100-chars/page rule of
        // thumb — calling a document "no text layer" while quoting CUSIPs read out of that text is a
        // contradiction. Checked AFTER the too-few-CUSIPs case below is ruled out by requiring > 1: a
        // 100-page schedule yielding ONE token is a suffix layout, not a column-split row.)
        if (cusips > 1 && res.rows().isEmpty()) {
            return "Found " + cusips + " CUSIP(s) — so the text layer DOES work — but completed 0 rows ("
                   + res.quarantined() + " quarantined): each CUSIP's coupon and/or maturity is not on the "
                   + "same line as it. PDFBox emits reading order, not table columns, so a schedule laid "
                   + "out in columns arrives one cell per line. Inspect cusipLines / firstLines.";
        }
        if (sparse) {
            return ocrEnabled
                    ? "Image-only (scanned) PDF and OCR IS enabled — OCR ran but still produced too little "
                      + "text; check tesseract is installed and muni.ocr.dpi is adequate."
                    : "Image-only (scanned) PDF: under 100 characters of text per page, i.e. no usable text "
                      + "layer. Enable OCR (muni.ocr.enabled=true, tesseract installed) and re-load.";
        }
        if (cusips <= 1) {
            return "Text extracted fine (" + pages + " pages) but contains " + cusips + " full CUSIP-9 "
                   + "token(s) — far fewer than a maturity schedule has. The schedule almost certainly "
                   + "prints a base CUSIP-6 once and only 2–3 character SUFFIXES per row"
                   + (baseCusip == null
                      ? ", and no base CUSIP was detected (the parser looks for the literal wording 'Base "
                        + "CUSIP'/'CUSIP Base')."
                      : ", base detected: " + baseCusip + ".")
                   + " Inspect cusipMentions + scheduleBlock to see the document's actual wording.";
        }
        return "Parsed " + res.rows().size() + " row(s) — the text path works on this document.";
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}

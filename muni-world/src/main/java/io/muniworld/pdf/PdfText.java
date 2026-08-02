package io.muniworld.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/**
 * A thin, well-understood wrapper over Apache PDFBox (ADR-0015): extract the text of a landed Official
 * Statement PDF so the {@link io.muniworld.extract.OfficialStatementParser} can read the maturity schedule.
 * PDFBox is the mature, Apache-2.0 choice; this class isolates the library call so the (harder, tested)
 * parsing logic works on plain text and never depends on PDF internals.
 *
 * <p>Text extraction preserves reading order but not table geometry — the parser is line/column-heuristic on
 * the extracted text, which handles the common columnar maturity schedule. Scanned (image-only) PDFs return
 * little/no text; OCR fallback is a later phase (ADR-0010/0015), and a text-poor result is a signal to
 * quarantine, not to guess.
 */
public final class PdfText {

    private PdfText() {
    }

    /** Extract all text from a PDF's bytes. Sorted by position so columns read left-to-right, top-to-bottom. */
    public static String extract(byte[] pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    /** Page count — a cheap sanity/telemetry signal alongside the extracted text. */
    public static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return doc.getNumberOfPages();
        }
    }
}

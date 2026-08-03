package io.muniworld.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * OCR fallback for <b>scanned</b> Official Statements (ADR-0015 Phase 3 / ADR-0010): when {@link PdfText}
 * returns little text, the PDF is image-only, so render each page (PDFBox) and OCR it by shelling out to
 * {@code tesseract} — local, no cloud. OCR text is lower-confidence by nature; downstream still confidence-
 * gates and quarantines (ADR-0011), and OCR-derived rows are flagged as such.
 *
 * <p>Runs only where {@code tesseract} is installed (the Pi); the sandbox/CI has no binary, so OCR is never
 * invoked there. The scanned-vs-native decision ({@link #isSparse}) is pure and testable.
 */
public final class OcrText {

    private final String tesseractBin;
    private final int dpi;

    public OcrText(String tesseractBin, int dpi) {
        this.tesseractBin = tesseractBin;
        this.dpi = dpi;
    }

    /**
     * Heuristic: a native-text PDF yields plenty of characters per page; a scanned one yields almost none.
     * Below ~100 chars/page we treat it as image-only and OCR it.
     */
    public static boolean isSparse(String nativeText, int pages) {
        if (pages <= 0) {
            return nativeText == null || nativeText.isBlank();
        }
        int chars = nativeText == null ? 0 : nativeText.strip().length();
        return chars / pages < 100;
    }

    /** Render each page to an image and OCR it with tesseract; concatenate the page texts. */
    public String extract(byte[] pdf) throws IOException {
        StringBuilder all = new StringBuilder();
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int page = 0; page < doc.getNumberOfPages(); page++) {
                BufferedImage img = renderer.renderImageWithDPI(page, dpi, ImageType.GRAY);
                all.append(ocrImage(img)).append('\n');
            }
        }
        return all.toString();
    }

    private String ocrImage(BufferedImage img) throws IOException {
        Path in = Files.createTempFile("muni-ocr", ".png");
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            Files.write(in, png.toByteArray());
            // tesseract <img> stdout  → OCR text to stdout
            Process p = new ProcessBuilder(tesseractBin, in.toString(), "stdout").redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("tesseract timed out");
            }
            return new String(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OCR interrupted", e);
        } finally {
            in.toFile().delete();
        }
    }
}

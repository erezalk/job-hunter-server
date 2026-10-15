package dev.erezalkalay.jobhunter.cv;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Plain-text extraction for CV uploads via Apache PDFBox. No OCR: a
 * scanned/image-only PDF simply yields little or no text, which
 * {@link CvService} detects and reports as a warning rather than pretending
 * extraction succeeded.
 */
@Component
public class CvTextExtractor {

    public static final String CONTENT_TYPE_PDF = "application/pdf";

    /**
     * Minimum extracted-text length below which we treat the result as
     * effectively empty (e.g. a scanned PDF with no embedded text layer)
     * rather than a genuine, if short, CV.
     */
    private static final int MIN_MEANINGFUL_TEXT_LENGTH = 40;

    public record ExtractionResult(String text, String status, String warning) {
    }

    public ExtractionResult extract(byte[] content) {
        try {
            String text = extractPdf(content);
            String trimmed = text == null ? "" : text.strip();
            if (trimmed.length() < MIN_MEANINGFUL_TEXT_LENGTH) {
                return new ExtractionResult(
                        trimmed,
                        Cv.STATUS_EMPTY,
                        "Little or no text could be extracted from this file. If it's a scanned/image-only "
                                + "PDF, re-export it as a text-based PDF and upload again.");
            }
            return new ExtractionResult(trimmed, Cv.STATUS_SUCCESS, null);
        } catch (IOException | RuntimeException e) {
            return new ExtractionResult(
                    "",
                    Cv.STATUS_FAILED,
                    "Could not read this file's contents: " + safeMessage(e));
        }
    }

    private String extractPdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}

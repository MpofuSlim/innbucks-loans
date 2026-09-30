package zw.co.innbucks.loans.core.document;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What an uploaded document must meet to be accepted (FR-SSB-005). The defaults reject what cannot be
 * read, not what is merely imperfect: an officer can still read a slightly soft photo, and an applicant
 * turned away for one is an applicant lost. Each is overridable per environment.
 */
@Data
@ConfigurationProperties(prefix = "loans.documents")
public class DocumentUploadProperties {

    /** Largest file accepted, in bytes, after base64 decoding. */
    private long maxSizeBytes = 5L * 1024 * 1024;

    /** Most pages a PDF may have: a payslip or an ID is a page or two, not a bundle. */
    private int maxPdfPages = 10;

    /** Shortest side, in pixels, of a payslip or national ID photo; smaller cannot be read. */
    private int minDocumentShortSidePx = 600;

    /** Shortest side, in pixels, of a signature image. */
    private int minSignatureShortSidePx = 30;

    /** Largest image accepted, in pixels, so a small file cannot unpack into a huge one. */
    private long maxImagePixels = 40_000_000L;

    /**
     * Least spread of brightness (standard deviation, 0–255) an image may have; below it the image is
     * one flat tone: blank, or too dark or too bright to read.
     */
    private double minContrast = 5.0;

    /**
     * Least edge sharpness a payslip or national ID photo may have (the 99.9th percentile of the
     * Laplacian, measured at up to 1024 pixels on the long side). Sharp text measures in the hundreds;
     * text blurred past reading measures under 20. Zero switches the check off.
     */
    private double minSharpness = 20.0;
}

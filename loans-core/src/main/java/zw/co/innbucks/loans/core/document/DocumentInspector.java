package zw.co.innbucks.loans.core.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.files.DecodedFile;
import zw.co.innbucks.loans.core.files.FileSignatureValidator;
import zw.co.innbucks.loans.core.files.FileSignatureValidator.FileKind;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks an uploaded document before it is kept (FR-SSB-005): that it decodes, is not over the size
 * limit, is a format accepted for that document, and can actually be read. A PDF must open without a
 * password and have pages; an image must decode completely, be large enough to read, not be one flat
 * tone, and (for a payslip or national ID) not be blurred past reading. A refusal says what is wrong
 * and what to do, in words the applicant can act on.
 *
 * <p>Nothing is written to disk and a PDF is only parsed, never rendered.
 */
@Slf4j
@Component
public class DocumentInspector {

    /** An image is decoded at most this large for measuring, so a big photo costs little memory. */
    private static final int DECODE_MAX_SIDE = 2048;

    private final FileSignatureValidator fileSignatureValidator;
    private final DocumentUploadProperties properties;

    public DocumentInspector(FileSignatureValidator fileSignatureValidator, DocumentUploadProperties properties) {
        this.fileSignatureValidator = fileSignatureValidator;
        this.properties = properties;
    }

    /** A checked upload, or why it was refused; both null when nothing was uploaded. */
    private record Inspection(DecodedFile file, DocumentProblem problem) {
    }

    /**
     * Every upload checked, and every problem reported together.
     *
     * @return the accepted files; an absent upload is simply not in the map
     * @throws DocumentRejectedException naming each upload that was refused
     */
    public Map<DocumentType, DecodedFile> inspectAll(Map<DocumentType, String> uploads) {
        Map<DocumentType, DecodedFile> accepted = new EnumMap<>(DocumentType.class);
        List<DocumentProblem> problems = new ArrayList<>();
        uploads.forEach((type, payload) -> {
            Inspection inspection = inspect(type, type.fieldName(), payload);
            if (inspection.problem() != null) {
                problems.add(inspection.problem());
            } else if (inspection.file() != null) {
                accepted.put(type, inspection.file());
            }
        });
        if (!problems.isEmpty()) {
            throw new DocumentRejectedException(problems);
        }
        return accepted;
    }

    /**
     * One upload checked.
     *
     * @param field the request field it arrived in, named in a refusal
     * @return the accepted file, or null when nothing was uploaded
     * @throws DocumentRejectedException the upload was refused
     */
    public DecodedFile inspectOne(DocumentType type, String field, String base64Payload) {
        Inspection inspection = inspect(type, field, base64Payload);
        if (inspection.problem() != null) {
            throw new DocumentRejectedException(List.of(inspection.problem()));
        }
        return inspection.file();
    }

    private Inspection inspect(DocumentType type, String field, String base64Payload) {
        if (StringUtils.isBlank(base64Payload)) {
            return new Inspection(null, null);
        }
        String payload = base64Payload;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        payload = payload.replaceAll("\\s", "");

        // Measured before decoding, so an oversized upload is refused without holding it twice.
        long size = decodedSize(payload);
        if (size > properties.getMaxSizeBytes()) {
            return refuse(type, field, DocumentProblemReason.TOO_LARGE, String.format(Locale.ROOT,
                    "The %s is %s; files up to %s are accepted. Please upload a smaller file or a lower-resolution photo.",
                    type.label(), megabytes(size), megabytes(properties.getMaxSizeBytes())));
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            return refuse(type, field, DocumentProblemReason.INVALID_ENCODING, String.format(
                    "The %s could not be read: the upload was damaged. Please upload it again.", type.label()));
        }
        if (bytes.length == 0) {
            return refuse(type, field, DocumentProblemReason.EMPTY, String.format(
                    "The %s upload is empty. Please upload the file again.", type.label()));
        }

        FileKind kind = fileSignatureValidator.classify(bytes);
        if (kind == FileKind.EXECUTABLE) {
            return refuse(type, field, DocumentProblemReason.EXECUTABLE, String.format(
                    "The %s was refused: it is a program, not a document or photo. Please upload %s.",
                    type.label(), acceptedFormats(type)));
        }
        if (!type.acceptedKinds().contains(kind)) {
            return refuse(type, field, DocumentProblemReason.UNSUPPORTED_TYPE, String.format(
                    "The %s must be %s.", type.label(), acceptedFormats(type)));
        }
        DocumentProblem problem = kind == FileKind.PDF
                ? inspectPdf(type, field, bytes)
                : inspectImage(type, field, bytes);
        if (problem != null) {
            return new Inspection(null, problem);
        }
        return new Inspection(new DecodedFile(bytes, kind.contentType(), AuditService.sha256Hex(bytes)), null);
    }

    private DocumentProblem inspectPdf(DocumentType type, String field, byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            int pages = pdf.getNumberOfPages();
            if (pages == 0) {
                return problem(type, field, DocumentProblemReason.NO_PAGES, String.format(
                        "The %s PDF has no pages. Please upload the complete document.", type.label()));
            }
            if (pages > properties.getMaxPdfPages()) {
                return problem(type, field, DocumentProblemReason.TOO_MANY_PAGES, String.format(
                        "The %s PDF has %d pages; up to %d are accepted. Please upload only the pages needed.",
                        type.label(), pages, properties.getMaxPdfPages()));
            }
            log.info("{} accepted: PDF, {} page(s), {} bytes", type, pages, bytes.length);
            return null;
        } catch (InvalidPasswordException e) {
            return problem(type, field, DocumentProblemReason.PASSWORD_PROTECTED, String.format(
                    "The %s is a password-protected PDF. Please upload a copy without a password.", type.label()));
        } catch (IOException | RuntimeException e) {
            log.info("{} refused: PDF could not be opened ({})", type, e.getClass().getSimpleName());
            return damaged(type, field);
        }
    }

    private DocumentProblem inspectImage(DocumentType type, String field, byte[] bytes) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return damaged(type, field);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return inspectImage(type, field, reader);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.info("{} refused: image could not be decoded ({})", type, e.getClass().getSimpleName());
            return damaged(type, field);
        }
    }

    private DocumentProblem inspectImage(DocumentType type, String field, ImageReader reader) throws IOException {
        // The header alone gives the size: a huge image is refused before it is decoded.
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if ((long) width * height > properties.getMaxImagePixels()) {
            return problem(type, field, DocumentProblemReason.DIMENSIONS_TOO_LARGE, String.format(
                    "The %s image is too large to process (%dx%d pixels). Please upload a smaller photo.",
                    type.label(), width, height));
        }
        int minShortSide = type.kycDocument()
                ? properties.getMinDocumentShortSidePx() : properties.getMinSignatureShortSidePx();
        if (Math.min(width, height) < minShortSide) {
            return problem(type, field, DocumentProblemReason.RESOLUTION_TOO_LOW, type.kycDocument()
                    ? String.format("The %s photo is too small to read (%dx%d pixels). Please retake it so the"
                            + " document fills the frame; it must be at least %d pixels on its shorter side.",
                    type.label(), width, height, minShortSide)
                    : String.format("The %s image is too small (%dx%d pixels); it must be at least %d pixels on"
                            + " its shorter side.", type.label(), width, height, minShortSide));
        }

        List<String> warnings = new ArrayList<>();
        reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
        ImageReadParam param = reader.getDefaultReadParam();
        int step = (int) Math.ceil(Math.max(width, height) / (double) DECODE_MAX_SIDE);
        if (step > 1) {
            param.setSourceSubsampling(step, step, 0, 0);
        }
        BufferedImage image;
        try {
            image = reader.read(0, param);
        } catch (IIOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Unsupported Image Type")) {
                // A valid JPEG in a colour space the JDK cannot convert (CMYK, from some scanners): its
                // header was read, so it is a document, just not one that can be measured here.
                log.info("{} accepted without quality measures: {}x{} in an unconvertible colour space",
                        type, width, height);
                return null;
            }
            throw e;
        }
        // A cut-off JPEG still decodes, grey below the cut; the decoder says so only in a warning.
        if (warnings.stream().anyMatch(DocumentInspector::isTruncation)) {
            log.info("{} refused: image is cut off ({})", type, warnings);
            return damaged(type, field);
        }

        ImageQuality quality = ImageQuality.measure(image);
        if (quality.contrast() < properties.getMinContrast()) {
            log.info("{} refused as blank: {}x{}, contrast {}", type, width, height, round(quality.contrast()));
            return problem(type, field, DocumentProblemReason.BLANK, type.kycDocument()
                    ? String.format("The %s photo looks blank, or is too dark or too bright to read. Please retake"
                    + " it in good light.", type.label())
                    : String.format("The %s is blank. Please sign again.", type.label()));
        }
        if (type.kycDocument() && properties.getMinSharpness() > 0
                && quality.sharpness() < properties.getMinSharpness()) {
            log.info("{} refused as blurred: {}x{}, sharpness {}", type, width, height, round(quality.sharpness()));
            return problem(type, field, DocumentProblemReason.BLURRED, String.format(
                    "The %s photo is too blurred to read. Please hold the camera steady and retake it in good light.",
                    type.label()));
        }
        // Logged so the thresholds can be tuned against real uploads.
        log.info("{} accepted: {}x{}, contrast {}, sharpness {}", type, width, height, round(quality.contrast()),
                round(quality.sharpness()));
        return null;
    }

    private static boolean isTruncation(String warning) {
        String lower = warning.toLowerCase(Locale.ROOT);
        return lower.contains("truncated") || lower.contains("premature end");
    }

    private static Inspection refuse(DocumentType type, String field, DocumentProblemReason reason, String message) {
        return new Inspection(null, problem(type, field, reason, message));
    }

    private static DocumentProblem problem(DocumentType type, String field, DocumentProblemReason reason,
                                           String message) {
        return new DocumentProblem(field, type, reason, message);
    }

    private static DocumentProblem damaged(DocumentType type, String field) {
        return problem(type, field, DocumentProblemReason.DAMAGED, String.format(
                "The %s could not be opened: the file is damaged or incomplete. Please upload it again.", type.label()));
    }

    private static String acceptedFormats(DocumentType type) {
        return type.kycDocument() ? "a PDF, PNG, JPEG or GIF file" : "a PNG, JPEG or GIF image";
    }

    /** The decoded size of padded or unpadded base64, without decoding it. */
    private static long decodedSize(String base64) {
        int padding = base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0;
        return base64.length() * 3L / 4 - padding;
    }

    private static String megabytes(long bytes) {
        double megabytes = bytes / (1024.0 * 1024.0);
        return megabytes == Math.rint(megabytes)
                ? String.format(Locale.ROOT, "%d MB", (long) megabytes)
                : String.format(Locale.ROOT, "%.1f MB", megabytes);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}

package zw.co.innbucks.loans.core.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.files.DecodedFile;
import zw.co.innbucks.loans.core.files.FileSignatureValidator;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static zw.co.innbucks.loans.core.document.TestDocuments.base64;
import static zw.co.innbucks.loans.core.document.TestDocuments.encode;

/**
 * Document upload checks (FR-SSB-005, AC-41): type, size, and whether the file can actually be read.
 * Every refusal names the document, what is wrong and what to do; every problem in an application is
 * reported together. The images are drawn here, so each case is exactly the condition it names.
 */
class DocumentInspectorTest {

    private static final BufferedImage PAGE = TestDocuments.page(1240, 1754);

    private DocumentUploadProperties properties;
    private DocumentInspector inspector;

    @BeforeEach
    void setUp() {
        properties = new DocumentUploadProperties();
        inspector = new DocumentInspector(new FileSignatureValidator(), properties);
    }

    private DocumentProblem refusal(DocumentType type, String payload) {
        try {
            inspector.inspectOne(type, type.fieldName(), payload);
        } catch (DocumentRejectedException e) {
            assertThat(e.getProblems()).hasSize(1);
            return e.getProblems().getFirst();
        }
        throw new AssertionError(type + " was accepted");
    }

    private DecodedFile accepted(DocumentType type, byte[] bytes) {
        DecodedFile file = inspector.inspectOne(type, type.fieldName(), base64(bytes));
        assertThat(file).isNotNull();
        assertThat(file.content()).isEqualTo(bytes);
        assertThat(file.sha256()).isEqualTo(AuditService.sha256Hex(bytes));
        return file;
    }

    // ── What is accepted ────────────────────────────────────────────────────

    @Test
    @DisplayName("a sharp payslip photo is accepted as PNG, JPEG or GIF, and a PDF as a PDF")
    void readableDocumentsAreAccepted() {
        assertThat(accepted(DocumentType.PAYSLIP, encode(PAGE, "png")).contentType()).isEqualTo("image/png");
        assertThat(accepted(DocumentType.PAYSLIP, encode(PAGE, "jpg")).contentType()).isEqualTo("image/jpeg");
        assertThat(accepted(DocumentType.NATIONAL_ID, encode(PAGE, "gif")).contentType()).isEqualTo("image/gif");
        assertThat(accepted(DocumentType.PAYSLIP, TestDocuments.pdf(2)).contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("a data-URL prefix and line breaks do not change the file, so they do not change its fingerprint")
    void dataUrlAndLineBreaksAreTolerated() {
        byte[] png = encode(PAGE, "png");
        String encoded = base64(png);
        DecodedFile wrapped = inspector.inspectOne(DocumentType.PAYSLIP, "payslipPicture",
                "data:image/png;base64," + encoded.substring(0, 76) + "\r\n" + encoded.substring(76));

        assertThat(wrapped.content()).isEqualTo(png);
        assertThat(wrapped.sha256()).isEqualTo(AuditService.sha256Hex(png));
    }

    @Test
    @DisplayName("a large phone photo is measured at a reduced size and accepted")
    void largePhotoIsAccepted() {
        assertThat(accepted(DocumentType.PAYSLIP, encode(TestDocuments.page(3000, 2250), "jpg")).contentType())
                .isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("a PDF protected only against editing still opens, so it is accepted")
    void ownerPasswordOnlyPdfIsAccepted() {
        accepted(DocumentType.PAYSLIP, TestDocuments.protectedPdf(""));
    }

    @Test
    @DisplayName("a signature from a signature pad (transparent background) is not taken for a blank one")
    void transparentSignatureIsAccepted() {
        assertThat(accepted(DocumentType.SIGNATURE, encode(TestDocuments.signature(400, 150, true), "png"))
                .contentType()).isEqualTo("image/png");
        accepted(DocumentType.WITNESS_SIGNATURE, encode(TestDocuments.signature(400, 150, false), "jpg"));
    }

    @Test
    @DisplayName("nothing uploaded is not a refusal: whether a document is required is decided elsewhere")
    void absentUploadIsNotChecked() {
        assertThat(inspector.inspectOne(DocumentType.PAYSLIP, "payslipPicture", null)).isNull();
        assertThat(inspector.inspectOne(DocumentType.PAYSLIP, "payslipPicture", "  ")).isNull();
    }

    // ── Type ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an executable is refused whatever it claims to be")
    void executableIsRefused() {
        DocumentProblem problem = refusal(DocumentType.SIGNATURE,
                "data:image/png;base64," + base64(new byte[]{'M', 'Z', (byte) 0x90, 0x00, 0x03}));

        assertThat(problem.reason()).isEqualTo(DocumentProblemReason.EXECUTABLE);
        assertThat(problem.message()).isEqualTo("The signature was refused: it is a program, not a document or"
                + " photo. Please upload a PNG, JPEG or GIF image.");
    }

    @Test
    @DisplayName("a format not accepted for the document is refused, naming what is accepted")
    void unsupportedTypeIsRefused() {
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes();
        DocumentProblem nationalId = refusal(DocumentType.NATIONAL_ID, base64(webp));
        assertThat(nationalId.reason()).isEqualTo(DocumentProblemReason.UNSUPPORTED_TYPE);
        assertThat(nationalId.message()).isEqualTo("The national ID must be a PDF, PNG, JPEG or GIF file.");

        // A signature is an image; a PDF is not one.
        DocumentProblem signature = refusal(DocumentType.SIGNATURE, base64(TestDocuments.pdf(1)));
        assertThat(signature.reason()).isEqualTo(DocumentProblemReason.UNSUPPORTED_TYPE);
        assertThat(signature.message()).isEqualTo("The signature must be a PNG, JPEG or GIF image.");
    }

    // ── Size ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a file over the limit is refused with its size and the limit, before it is decoded")
    void oversizedFileIsRefused() {
        properties.setMaxSizeBytes(1024 * 1024);
        // 2 MiB of base64 is 1.5 MiB of file; it is never decoded, so its content does not matter.
        DocumentProblem problem = refusal(DocumentType.PAYSLIP, "A".repeat(2 * 1024 * 1024));

        assertThat(problem.reason()).isEqualTo(DocumentProblemReason.TOO_LARGE);
        assertThat(problem.message()).isEqualTo("The payslip is 1.5 MB; files up to 1 MB are accepted. Please upload"
                + " a smaller file or a lower-resolution photo.");
    }

    @Test
    @DisplayName("a file exactly at the limit is accepted")
    void fileAtTheLimitIsAccepted() {
        byte[] png = encode(PAGE, "png");
        properties.setMaxSizeBytes(png.length);

        accepted(DocumentType.PAYSLIP, png);
    }

    // ── Unreadable ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("content that is not base64, or decodes to nothing, is refused")
    void undecodableOrEmptyIsRefused() {
        DocumentProblem notBase64 = refusal(DocumentType.SIGNATURE, "signed: R. Chikwanha");
        assertThat(notBase64.reason()).isEqualTo(DocumentProblemReason.INVALID_ENCODING);
        assertThat(notBase64.message())
                .isEqualTo("The signature could not be read: the upload was damaged. Please upload it again.");

        DocumentProblem empty = refusal(DocumentType.PAYSLIP, "data:application/pdf;base64,");
        assertThat(empty.reason()).isEqualTo(DocumentProblemReason.EMPTY);
        assertThat(empty.message()).isEqualTo("The payslip upload is empty. Please upload the file again.");
    }

    @Test
    @DisplayName("a photo cut off in upload is refused as damaged, though its first part would decode")
    void truncatedImagesAreRefused() {
        byte[] jpeg = encode(PAGE, "jpg");
        byte[] png = encode(PAGE, "png");
        String damaged = "The payslip could not be opened: the file is damaged or incomplete. Please upload it again.";

        for (byte[] cut : new byte[][]{Arrays.copyOf(jpeg, jpeg.length * 6 / 10), Arrays.copyOf(png, png.length / 2)}) {
            DocumentProblem problem = refusal(DocumentType.PAYSLIP, base64(cut));
            assertThat(problem.reason()).isEqualTo(DocumentProblemReason.DAMAGED);
            assertThat(problem.message()).isEqualTo(damaged);
        }
    }

    @Test
    @DisplayName("a PDF that is damaged, needs a password, has no pages or too many is refused, each saying why")
    void unreadablePdfsAreRefused() {
        byte[] pdf = TestDocuments.pdf(1);
        DocumentProblem cut = refusal(DocumentType.PAYSLIP, base64(Arrays.copyOf(pdf, pdf.length / 2)));
        assertThat(cut.reason()).isEqualTo(DocumentProblemReason.DAMAGED);
        assertThat(refusal(DocumentType.PAYSLIP, base64("%PDF-1.7\nnot really a pdf".getBytes())).reason())
                .isEqualTo(DocumentProblemReason.DAMAGED);

        DocumentProblem locked = refusal(DocumentType.PAYSLIP, base64(TestDocuments.protectedPdf("open-sesame")));
        assertThat(locked.reason()).isEqualTo(DocumentProblemReason.PASSWORD_PROTECTED);
        assertThat(locked.message()).isEqualTo("The payslip is a password-protected PDF. Please upload a copy without"
                + " a password.");

        DocumentProblem noPages = refusal(DocumentType.NATIONAL_ID, base64(TestDocuments.pdf(0)));
        assertThat(noPages.reason()).isEqualTo(DocumentProblemReason.NO_PAGES);
        assertThat(noPages.message()).isEqualTo("The national ID PDF has no pages. Please upload the complete document.");

        DocumentProblem tooMany = refusal(DocumentType.PAYSLIP, base64(TestDocuments.pdf(11)));
        assertThat(tooMany.reason()).isEqualTo(DocumentProblemReason.TOO_MANY_PAGES);
        assertThat(tooMany.message()).isEqualTo("The payslip PDF has 11 pages; up to 10 are accepted. Please upload"
                + " only the pages needed.");
    }

    // ── Image quality ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a photo too small to read is refused with its size and the minimum")
    void lowResolutionIsRefused() {
        DocumentProblem payslip = refusal(DocumentType.PAYSLIP, base64(encode(TestDocuments.page(480, 320), "png")));
        assertThat(payslip.reason()).isEqualTo(DocumentProblemReason.RESOLUTION_TOO_LOW);
        assertThat(payslip.message()).isEqualTo("The payslip photo is too small to read (480x320 pixels). Please"
                + " retake it so the document fills the frame; it must be at least 600 pixels on its shorter side.");

        DocumentProblem signature = refusal(DocumentType.SIGNATURE,
                base64(encode(TestDocuments.signature(60, 20, true), "png")));
        assertThat(signature.reason()).isEqualTo(DocumentProblemReason.RESOLUTION_TOO_LOW);
        assertThat(signature.message()).isEqualTo("The signature image is too small (60x20 pixels); it must be at"
                + " least 30 pixels on its shorter side.");
    }

    @Test
    @DisplayName("an image over the pixel limit is refused from its header, before it is decoded")
    void hugeDimensionsAreRefused() {
        properties.setMaxImagePixels(1_000_000);

        DocumentProblem problem = refusal(DocumentType.PAYSLIP, base64(encode(PAGE, "png")));

        assertThat(problem.reason()).isEqualTo(DocumentProblemReason.DIMENSIONS_TOO_LARGE);
        assertThat(problem.message()).isEqualTo("The payslip image is too large to process (1240x1754 pixels). Please"
                + " upload a smaller photo.");
    }

    @Test
    @DisplayName("a blank, black or white photo is refused; an empty signature pad is a blank signature")
    void blankImagesAreRefused() {
        for (Color tone : new Color[]{Color.WHITE, Color.BLACK, new Color(250, 250, 248)}) {
            DocumentProblem problem = refusal(DocumentType.NATIONAL_ID,
                    base64(encode(TestDocuments.flat(1000, 700, tone, false), "png")));
            assertThat(problem.reason()).isEqualTo(DocumentProblemReason.BLANK);
            assertThat(problem.message()).isEqualTo("The national ID photo looks blank, or is too dark or too bright"
                    + " to read. Please retake it in good light.");
        }

        DocumentProblem signature = refusal(DocumentType.WITNESS_SIGNATURE,
                base64(encode(TestDocuments.flat(400, 150, Color.WHITE, true), "png")));
        assertThat(signature.reason()).isEqualTo(DocumentProblemReason.BLANK);
        assertThat(signature.message()).isEqualTo("The witness's signature is blank. Please sign again.");
    }

    @Test
    @DisplayName("a payslip blurred past reading is refused; a soft one is not")
    void blurredDocumentIsRefused() {
        DocumentProblem problem = refusal(DocumentType.PAYSLIP, base64(encode(TestDocuments.blurred(PAGE, 8), "png")));
        assertThat(problem.reason()).isEqualTo(DocumentProblemReason.BLURRED);
        assertThat(problem.message()).isEqualTo("The payslip photo is too blurred to read. Please hold the camera"
                + " steady and retake it in good light.");

        // Slightly soft: an officer can still read it, so the applicant is not turned away.
        accepted(DocumentType.PAYSLIP, encode(TestDocuments.blurred(PAGE, 2), "png"));
    }

    @Test
    @DisplayName("sharpness is not asked of a signature, and the check can be switched off")
    void sharpnessCheckScope() {
        accepted(DocumentType.SIGNATURE, encode(TestDocuments.blurred(TestDocuments.signature(400, 150, false), 4), "png"));

        properties.setMinSharpness(0);
        accepted(DocumentType.PAYSLIP, encode(TestDocuments.blurred(PAGE, 8), "png"));
    }

    // ── An application's documents together ─────────────────────────────────

    @Test
    @DisplayName("every refused document of an application is reported at once, each with its field")
    void everyProblemIsReportedTogether() {
        Map<DocumentType, String> uploads = new LinkedHashMap<>();
        uploads.put(DocumentType.PAYSLIP, base64(encode(TestDocuments.blurred(PAGE, 8), "png")));
        uploads.put(DocumentType.NATIONAL_ID, base64(encode(PAGE, "jpg")));
        uploads.put(DocumentType.SIGNATURE, base64(encode(TestDocuments.flat(400, 150, Color.WHITE, true), "png")));
        uploads.put(DocumentType.WITNESS_SIGNATURE, null);

        assertThatThrownBy(() -> inspector.inspectAll(uploads))
                .isInstanceOf(DocumentRejectedException.class)
                .hasMessage("The payslip photo is too blurred to read. Please hold the camera steady and retake it in"
                        + " good light. The signature is blank. Please sign again.")
                .satisfies(e -> assertThat(((DocumentRejectedException) e).getProblems())
                        .extracting(DocumentProblem::field, DocumentProblem::documentType, DocumentProblem::reason)
                        .containsExactly(
                                tuple("payslipPicture", DocumentType.PAYSLIP, DocumentProblemReason.BLURRED),
                                tuple("signature", DocumentType.SIGNATURE, DocumentProblemReason.BLANK)));
    }

    @Test
    @DisplayName("an application whose documents all pass gets each back, absent ones left out")
    void acceptedApplicationDocuments() {
        Map<DocumentType, String> uploads = new LinkedHashMap<>();
        uploads.put(DocumentType.PAYSLIP, base64(TestDocuments.pdf(1)));
        uploads.put(DocumentType.NATIONAL_ID, null);
        uploads.put(DocumentType.SIGNATURE, base64(encode(TestDocuments.signature(400, 150, true), "png")));

        assertThat(inspector.inspectAll(uploads)).containsOnlyKeys(DocumentType.PAYSLIP, DocumentType.SIGNATURE);
    }
}

package zw.co.innbucks.loans.core.document;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.LoanApprovalException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.files.DecodedFile;
import zw.co.innbucks.loans.core.files.FileSignatureValidator;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.LoanSpecification;
import zw.co.innbucks.loans.core.loan.PayslipFraudDetector;
import zw.co.innbucks.loans.core.loan.PayslipFraudReason;
import zw.co.innbucks.loans.core.loan.PayslipReviewService;
import zw.co.innbucks.loans.core.loan.PayslipReviewStatus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A loan's documents with their version history, and a log of every access (FR-SSB-009). A document is
 * never overwritten: a replacement is the next version and every earlier one stays. Reading a document's
 * content and uploading a version are each logged in the same transaction, so a document is never handed
 * out without the record of it. Listing versions without their content is not an access.
 */
@Service
public class LoanDocumentService {

    /** Credit has not decided, or asked for more information: the file may still change. */
    private static final Set<InternalApprovalStatus> AMENDABLE_CREDIT_STATUSES =
            Set.of(InternalApprovalStatus.PENDING, InternalApprovalStatus.RETURNED);
    /** Not refused or failed at SSB. */
    private static final Set<LoanApprovalStatus> AMENDABLE_SSB_STATUSES =
            Set.of(LoanApprovalStatus.NEW, LoanApprovalStatus.PROCESSING, LoanApprovalStatus.APPROVED);

    private final LoanDocumentRepository loanDocumentRepository;
    private final LoanDocumentAccessRepository loanDocumentAccessRepository;
    private final LoanRepository loanRepository;
    private final FileSignatureValidator fileSignatureValidator;
    private final AuthService authService;
    private final PayslipFraudDetector payslipFraudDetector;
    private final PayslipReviewService payslipReviewService;

    public LoanDocumentService(LoanDocumentRepository loanDocumentRepository,
                               LoanDocumentAccessRepository loanDocumentAccessRepository,
                               LoanRepository loanRepository, FileSignatureValidator fileSignatureValidator,
                               AuthService authService, PayslipFraudDetector payslipFraudDetector,
                               PayslipReviewService payslipReviewService) {
        this.loanDocumentRepository = loanDocumentRepository;
        this.loanDocumentAccessRepository = loanDocumentAccessRepository;
        this.loanRepository = loanRepository;
        this.fileSignatureValidator = fileSignatureValidator;
        this.authService = authService;
        this.payslipFraudDetector = payslipFraudDetector;
        this.payslipReviewService = payslipReviewService;
    }

    /**
     * An application's documents, decoded and checked before anything is saved: an undecodable or
     * executable file is refused, and a payslip or national ID must be a PDF, PNG, JPEG or GIF.
     */
    public Map<DocumentType, DecodedFile> decodeApplication(LoanApplicationRequest request) {
        Map<DocumentType, String> uploads = new LinkedHashMap<>();
        uploads.put(DocumentType.PAYSLIP, request.getPayslipPicture());
        uploads.put(DocumentType.NATIONAL_ID, request.getNationalIdPicture());
        uploads.put(DocumentType.SIGNATURE, request.getSignature());
        uploads.put(DocumentType.WITNESS_SIGNATURE,
                request.getWitness() == null ? null : request.getWitness().getSignature());
        Map<DocumentType, DecodedFile> decoded = new EnumMap<>(DocumentType.class);
        uploads.forEach((type, payload) -> {
            DecodedFile file = fileSignatureValidator.decodeBase64Document(type.fieldName(), payload, type.kycDocument());
            if (file != null) {
                decoded.put(type, file);
            }
        });
        return decoded;
    }

    /** Version 1 of each document, as the application's, in the caller's transaction. */
    public void storeApplication(Loan loan, Map<DocumentType, DecodedFile> files, String uploadedBy) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        files.forEach((type, file) -> store(loan.getId(), type, 1, DocumentOrigin.APPLICATION, file, null,
                uploadedBy, now));
    }

    /** The current version of each document, without content: what a loan's detail lists. */
    public List<LoanDocumentSummary> currentSummaries(Long loanId) {
        return new ArrayList<>(LoanDocumentSummary.currentOf(loanDocumentRepository.findSummaries(loanId)).values());
    }

    /**
     * Every version of every document, without content, in the order the loan's detail lists them (payslip,
     * national ID, signature, witness signature), each oldest first. Not an access: no content is handed out.
     */
    @Transactional(readOnly = true)
    public List<LoanDocumentSummary> history(Long loanId, LoanReadScope scope) {
        requireReadable(loanId, scope);
        return loanDocumentRepository.findSummaries(loanId).stream()
                .sorted(Comparator.comparing(LoanDocumentSummary::documentType).thenComparing(LoanDocumentSummary::version))
                .toList();
    }

    /**
     * A document's content, the current version or a given one, logged as a view before it is handed out.
     *
     * @throws NotFoundException no such loan, not one the caller may read, or no such document
     */
    @Transactional
    public LoanDocumentContent view(Long loanId, DocumentType type, Integer version, LoanReadScope scope) {
        requireReadable(loanId, scope);
        LoanDocument document = (version == null
                ? loanDocumentRepository.findFirstByLoanIdAndDocumentTypeOrderByVersionDesc(loanId, type)
                : loanDocumentRepository.findByLoanIdAndDocumentTypeAndVersion(loanId, type, version))
                .orElseThrow(() -> new NotFoundException(version == null
                        ? String.format("Loan %d has no %s", loanId, type)
                        : String.format("Loan %d has no %s version %d", loanId, type, version)));
        logAccess(document, DocumentAccessAction.VIEW, authService.getLoggedInUsername(),
                LocalDateTime.now(ZoneOffset.UTC));
        return LoanDocumentContent.of(document);
    }

    /**
     * Replaces a payslip or national ID with a new version, kept beside the old ones. Only while Credit has
     * not decided (or has asked for more information), and only on a loan SSB has not refused. A new payslip
     * is checked for fraud like an application's (FR-SSB-007) and, if it raises a concern, holds the loan
     * for review again, however it was reviewed before.
     *
     * @throws NotFoundException no such loan, or not one the caller may read
     * @throws ConflictException the loan's documents can no longer change, or the file is the current one
     */
    @Transactional
    public LoanDocumentSummary amend(Long loanId, DocumentType type, AmendDocumentRequest request,
                                     LoanReadScope scope) {
        if (!type.amendable()) {
            throw new LoanApprovalException(type + " cannot be replaced: it is part of the signed application");
        }
        if (StringUtils.isBlank(request.getReason())) {
            throw new LoanApprovalException("Reason is required");
        }
        requireReadable(loanId, scope);
        Loan loan = loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new NotFoundException("Loan " + loanId + " not found"));
        requireAmendable(loan);
        DecodedFile file = fileSignatureValidator.decodeBase64Document("content", request.getContent(), true);
        if (file == null) {
            throw new LoanApprovalException("Content is required");
        }
        LoanDocument current = loanDocumentRepository.findFirstByLoanIdAndDocumentTypeOrderByVersionDesc(loanId, type)
                .orElse(null);
        if (current != null && current.getSha256().equals(file.sha256())) {
            throw new ConflictException(String.format("This %s is the same file as version %d", type,
                    current.getVersion()));
        }

        String username = authService.getLoggedInUsername();
        LoanDocument saved = store(loanId, type, current == null ? 1 : current.getVersion() + 1,
                DocumentOrigin.AMENDMENT, file, request.getReason().trim(), username,
                LocalDateTime.now(ZoneOffset.UTC));

        if (type == DocumentType.PAYSLIP) {
            loan.setPayslipSha256(file.sha256());
            // As at application: two loans with one payslip must not pass each other unseen.
            loanRepository.lockApplicant("loan-application:payslip:" + file.sha256());
            // Only what the new file can change: the captured deductions are the same as before, and were
            // judged then.
            List<PayslipFraudDetector.Finding> findings = payslipFraudDetector.findingsFor(loan).stream()
                    .filter(finding -> finding.reason() != PayslipFraudReason.DEDUCTIONS_EXCEED_GROSS_LESS_NET)
                    .toList();
            if (!findings.isEmpty()) {
                // A decision on the old payslip says nothing about this one.
                loan.setPayslipReviewStatus(PayslipReviewStatus.PENDING);
                loan.setPayslipReviewedBy(null);
                loan.setPayslipReviewedAt(null);
                loan.setPayslipReviewComment(null);
            }
            loanRepository.save(loan);
            if (!findings.isEmpty()) {
                payslipReviewService.hold(loan, findings);
            }
        }
        return LoanDocumentSummary.of(saved);
    }

    /** Every view and upload of the loan's documents, newest first. */
    @Transactional(readOnly = true)
    public List<DocumentAccessResponse> accessLog(Long loanId) {
        if (!loanRepository.existsById(loanId)) {
            throw new NotFoundException("Loan " + loanId + " not found");
        }
        return loanDocumentAccessRepository.findByLoanIdOrderByIdDesc(loanId).stream()
                .map(DocumentAccessResponse::of).toList();
    }

    private LoanDocument store(Long loanId, DocumentType type, int version, DocumentOrigin origin, DecodedFile file,
                               String reason, String uploadedBy, LocalDateTime at) {
        LoanDocument saved = loanDocumentRepository.save(LoanDocument.builder()
                .loanId(loanId)
                .documentType(type)
                .version(version)
                .origin(origin)
                .content(file.content())
                .contentType(file.contentType())
                .sizeBytes(file.size())
                .sha256(file.sha256())
                .reason(reason)
                .uploadedBy(uploadedBy)
                .uploadedAt(at)
                .build());
        logAccess(saved, DocumentAccessAction.UPLOAD, uploadedBy, at);
        return saved;
    }

    private void logAccess(LoanDocument document, DocumentAccessAction action, String performedBy, LocalDateTime at) {
        loanDocumentAccessRepository.save(LoanDocumentAccess.builder()
                .loanId(document.getLoanId())
                .documentId(document.getId())
                .documentType(document.getDocumentType())
                .version(document.getVersion())
                .action(action)
                .performedBy(performedBy)
                .performedAt(at)
                .build());
    }

    /** Out of scope reads as missing, as on every other loan read. */
    private void requireReadable(Long loanId, LoanReadScope scope) {
        boolean readable = scope.platformWide()
                ? loanRepository.existsById(loanId)
                : loanRepository.exists(LoanSpecification.readableBy(loanId, scope));
        if (!readable) {
            throw new NotFoundException("Loan " + loanId + " not found");
        }
    }

    private static void requireAmendable(Loan loan) {
        InternalApprovalStatus credit = loan.getInternalApprovalStatus() == null
                ? InternalApprovalStatus.PENDING : loan.getInternalApprovalStatus();
        LoanApprovalStatus ssb = loan.getLoanApprovalStatus() == null
                ? LoanApprovalStatus.NEW : loan.getLoanApprovalStatus();
        if (!AMENDABLE_CREDIT_STATUSES.contains(credit) || !AMENDABLE_SSB_STATUSES.contains(ssb)) {
            throw new ConflictException(String.format(
                    "Documents of loan %s can no longer be replaced (SSB status %s, credit status %s)",
                    loan.getReference(), ssb, credit));
        }
    }
}

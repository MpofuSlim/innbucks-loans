package zw.co.innbucks.loans.core.instrument;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.IncompleteApplicationException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.files.DecodedFile;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.LoanSpecification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static zw.co.innbucks.loans.core.audit.AuditService.sha256Hex;

/**
 * Electronic signature of the loan agreement and the SSB deduction authority (FR-SSB-013).
 *
 * <p>An application is signed when it is submitted: the applicant has read each instrument filled with their
 * terms ({@link #preview}), accepted the version in force, and signed. What is kept for each instrument is the
 * exact text signed with its fingerprint, the fingerprint of the signature it was signed with, and the evidence
 * of the signing: when, through whose session, from which device and address, authenticated how. Each record
 * is sealed by a hash over all of it, and none is ever changed.
 *
 * <p>An instrument is signed only once its wording has been published; until then applications are taken as
 * before, without it. So wording pending from Legal holds up nothing, and publishing it is what switches
 * signing on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignedInstrumentService {

    /** Visible ASCII: an app's installation id, a browser's stored id, a hardware id. */
    private static final Pattern DEVICE_ID = Pattern.compile("[\\x21-\\x7E]{1,128}");
    private static final Pattern SIGNER_AUTHENTICATION = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final String DEVICE_HEADER = "X-Device-Id";
    private static final String SIGNER_AUTHENTICATION_HEADER = "X-Signer-Authentication";
    private static final char SEPARATOR = '\u001F';
    /** The signing time as the seal writes it: UTC, always six decimals, as the column holds it. */
    private static final DateTimeFormatter SEALED_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

    private final InstrumentTemplateService templateService;
    private final SignedInstrumentRepository instrumentRepository;
    private final LoanRepository loanRepository;
    private final MarketTimeZone marketTimeZone;

    /**
     * The instruments this application must be signed against: the version in force of each published
     * instrument. Checked before the application touches business state, and every problem reported at once.
     *
     * @throws IncompleteApplicationException an instrument not accepted, or a signature or device missing
     * @throws ConflictException              the applicant accepted a version no longer in force
     */
    public List<InstrumentTemplate> requireAccepted(LoanApplicationRequest application,
                                                   Map<DocumentType, DecodedFile> documents,
                                                   SigningContext signing) {
        Map<String, String> problems = new TreeMap<>();
        List<String> changed = new ArrayList<>();
        List<InstrumentTemplate> due = new ArrayList<>();
        for (InstrumentType type : InstrumentType.values()) {
            Integer accepted = acceptedVersion(application, type);
            Optional<InstrumentTemplate> current = templateService.currentTemplate(type);
            if (current.isEmpty()) {
                if (accepted != null) {
                    problems.put(type.versionField(), "There is no published " + type.label() + " to accept");
                }
                continue;
            }
            InstrumentTemplate template = current.get();
            due.add(template);
            if (accepted == null) {
                problems.put(type.versionField(), String.format(
                        "The applicant must accept the %s: version %d is in force", type.label(), template.getVersion()));
            } else if (accepted != template.getVersion()) {
                changed.add(String.format("The %s has changed since the applicant accepted version %d: version %d is"
                                + " in force. Show them version %d and ask them to accept it.", type.label(), accepted,
                        template.getVersion(), template.getVersion()));
            }
        }
        if (!due.isEmpty()) {
            String signed = due.stream().map(template -> template.getInstrumentType().label())
                    .collect(Collectors.joining(" and "));
            if (!documents.containsKey(DocumentType.SIGNATURE)) {
                problems.put(DocumentType.SIGNATURE.fieldName(), "The applicant's signature is required to sign the "
                        + signed);
            }
            if (signing.deviceId() == null || signing.deviceId().isBlank()) {
                problems.put(DEVICE_HEADER, "The signing device is required to sign the " + signed
                        + ": send it in the " + DEVICE_HEADER + " header");
            } else if (!DEVICE_ID.matcher(signing.deviceId()).matches()) {
                problems.put(DEVICE_HEADER, "The device id must be 1 to 128 visible characters");
            }
            if (signing.signerAuthentication() != null
                    && !SIGNER_AUTHENTICATION.matcher(signing.signerAuthentication()).matches()) {
                problems.put(SIGNER_AUTHENTICATION_HEADER,
                        "Must be 1 to 64 letters, digits, '_', '.', ':' or '-', such as SUPERAPP_PIN");
            }
        }
        if (!problems.isEmpty()) {
            throw new IncompleteApplicationException(problems);
        }
        if (!changed.isEmpty()) {
            throw new ConflictException(String.join(" ", changed));
        }
        return due;
    }

    /**
     * Records the application's instruments as signed, with the loan's terms as they were saved. Runs in the
     * application's transaction, after the loan and its documents are saved.
     */
    public List<SignedInstrument> sign(Loan loan, List<InstrumentTemplate> templates,
                                       Map<DocumentType, DecodedFile> documents, SigningContext signing,
                                       String signedBy) {
        if (templates.isEmpty()) {
            return List.of();
        }
        LocalDateTime signedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        LocalDate signedDate = marketTimeZone.localDay(signedAt);
        DecodedFile signature = Objects.requireNonNull(documents.get(DocumentType.SIGNATURE),
                "The applicant's signature is checked by requireAccepted before anything is signed");
        DecodedFile witnessSignature = documents.get(DocumentType.WITNESS_SIGNATURE);
        List<SignedInstrument> instruments = new ArrayList<>();
        for (InstrumentTemplate template : templates) {
            String content = InstrumentTerms.render(template.getBody(), loan, signedDate);
            SignedInstrument.SignedInstrumentBuilder instrument = SignedInstrument.builder()
                    .loanId(loan.getId())
                    .instrumentType(template.getInstrumentType())
                    .templateVersion(template.getVersion())
                    .title(template.getTitle())
                    .content(content)
                    .contentSha256(sha256Hex(content))
                    .signatureSha256(signature.sha256())
                    .witnessSignatureSha256(witnessSignature == null ? null : witnessSignature.sha256())
                    .signedBy(signedBy)
                    .signedAt(signedAt)
                    .deviceId(signing.deviceId())
                    .ipAddress(Objects.requireNonNullElse(clean(signing.ipAddress(), 64), "unknown"))
                    .forwardedFor(clean(signing.forwardedFor(), 512))
                    .userAgent(clean(signing.userAgent(), 512))
                    .authenticationMethod(Objects.requireNonNullElse(clean(signing.authenticationMethod(), 64),
                            "unknown"))
                    .signerAuthentication(signing.signerAuthentication());
            SignedInstrument unsealed = instrument.build();
            instruments.add(instrument.evidenceSha256(evidenceSha256(unsealed)).build());
        }
        List<SignedInstrument> saved = instrumentRepository.saveAll(instruments);
        log.info("Loan {} signed: {}", loan.getId(), saved.stream()
                .map(signedInstrument -> signedInstrument.getInstrumentType() + " v" + signedInstrument.getTemplateVersion())
                .toList());
        return saved;
    }

    /**
     * Each published instrument filled with this application's terms, as it will be signed today: what the
     * applicant reads before accepting it. The loan is the one the application would become, not saved.
     */
    public List<InstrumentPreview> preview(Loan loan) {
        LocalDate today = marketTimeZone.today();
        List<InstrumentPreview> previews = new ArrayList<>();
        for (InstrumentType type : InstrumentType.values()) {
            templateService.currentTemplate(type).ifPresent(template -> {
                String content = InstrumentTerms.render(template.getBody(), loan, today);
                previews.add(new InstrumentPreview(type, template.getVersion(), template.getTitle(), content,
                        sha256Hex(content)));
            });
        }
        return previews;
    }

    /**
     * The instruments a loan was signed with, each checked against its fingerprints: {@code intact} is false for
     * a record whose text or evidence no longer matches what was sealed when it was signed.
     *
     * @throws NotFoundException no such loan, or not one the caller may read
     */
    @Transactional(readOnly = true)
    public List<SignedInstrumentResponse> forLoan(Long loanId, LoanReadScope scope) {
        boolean readable = scope.platformWide()
                ? loanRepository.existsById(loanId)
                : loanRepository.exists(LoanSpecification.readableBy(loanId, scope));
        if (!readable) {
            throw new NotFoundException("Loan " + loanId + " not found");
        }
        return instrumentRepository.findByLoanIdOrderByInstrumentType(loanId).stream()
                .map(SignedInstrumentService::response)
                .toList();
    }

    private static SignedInstrumentResponse response(SignedInstrument instrument) {
        boolean intact = sha256Hex(instrument.getContent()).equals(instrument.getContentSha256())
                && evidenceSha256(instrument).equals(instrument.getEvidenceSha256());
        if (!intact) {
            log.error("Signed instrument {} of loan {} no longer matches its seal", instrument.getId(),
                    instrument.getLoanId());
        }
        return new SignedInstrumentResponse(instrument.getInstrumentType(), instrument.getTemplateVersion(),
                instrument.getTitle(), instrument.getContent(), instrument.getContentSha256(),
                instrument.getSignatureSha256(), instrument.getWitnessSignatureSha256(), instrument.getSignedBy(),
                instrument.getSignedAt(), instrument.getDeviceId(), instrument.getIpAddress(),
                instrument.getForwardedFor(), instrument.getUserAgent(), instrument.getAuthenticationMethod(),
                instrument.getSignerAuthentication(), instrument.getEvidenceSha256(), intact);
    }

    /**
     * The seal: a SHA-256 over every field of the record, the text by its own fingerprint. Recomputed on every
     * read, so a record altered in the database shows as not intact. It can be recomputed without this code:
     * the SHA-256 (hex) of the UTF-8 of these, joined by U+001F, an absent value as empty — loan id, instrument
     * type, template version, title, content SHA-256, signature SHA-256, witness signature SHA-256, signed by,
     * signed at (UTC, {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS}), device id, IP address, forwarded for, user agent,
     * authentication method, signer authentication.
     */
    static String evidenceSha256(SignedInstrument instrument) {
        return sha256Hex(String.join(String.valueOf(SEPARATOR),
                String.valueOf(instrument.getLoanId()),
                instrument.getInstrumentType().name(),
                String.valueOf(instrument.getTemplateVersion()),
                instrument.getTitle(),
                instrument.getContentSha256(),
                instrument.getSignatureSha256(),
                Objects.toString(instrument.getWitnessSignatureSha256(), ""),
                instrument.getSignedBy(),
                SEALED_TIME.format(instrument.getSignedAt()),
                instrument.getDeviceId(),
                instrument.getIpAddress(),
                Objects.toString(instrument.getForwardedFor(), ""),
                Objects.toString(instrument.getUserAgent(), ""),
                instrument.getAuthenticationMethod(),
                Objects.toString(instrument.getSignerAuthentication(), "")));
    }

    private static Integer acceptedVersion(LoanApplicationRequest application, InstrumentType type) {
        return switch (type) {
            case LOAN_AGREEMENT -> application.getLoanAgreementVersion();
            case SSB_DEDUCTION_AUTHORITY -> application.getDeductionAuthorityVersion();
        };
    }

    /** Control characters removed, cut to the column's size; blank is absent. */
    private static String clean(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String printable = value.replaceAll("\\p{Cntrl}", "").strip();
        if (printable.isEmpty()) {
            return null;
        }
        return printable.length() > maxLength ? printable.substring(0, maxLength) : printable;
    }
}

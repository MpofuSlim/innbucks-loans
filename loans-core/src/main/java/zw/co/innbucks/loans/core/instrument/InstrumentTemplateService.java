package zw.co.innbucks.loans.core.instrument;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The published wording of the loan agreement and the SSB deduction authority (FR-SSB-013). Publishing adds
 * the next version and never changes an earlier one. Once an instrument has a published version, every
 * application must be signed against its latest version; until then, applications are taken without it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentTemplateService {

    private final InstrumentTemplateRepository templateRepository;
    private final LoanRepository loanRepository;
    private final AuthService authService;

    /**
     * Publishes new wording as the instrument's next version, in force from now.
     *
     * @throws IllegalArgumentException the wording names a placeholder that is not a loan term
     */
    @Transactional
    public InstrumentTemplateResponse publish(PublishInstrumentTemplateRequest request) {
        List<String> unknown = InstrumentTerms.unknownPlaceholders(request.body());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown placeholder" + (unknown.size() == 1 ? " " : "s ")
                    + String.join(", ", unknown.stream().map(name -> "{{" + name + "}}").toList())
                    + ": a placeholder must be one of the loan terms listed by GET /instrument-templates/placeholders");
        }
        String publishedBy = authService.getLoggedInUsername();
        // Two publications of one instrument take the next version one after the other.
        loanRepository.lockApplicant("instrument-template:" + request.instrumentType());
        InstrumentTemplate template = templateRepository.save(InstrumentTemplate.builder()
                .instrumentType(request.instrumentType())
                .version(templateRepository.findLatestVersion(request.instrumentType()) + 1)
                .title(request.title().strip())
                .body(request.body())
                .publishedBy(publishedBy)
                .publishedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS))
                .build());
        log.info("Published {} version {} by {}", template.getInstrumentType(), template.getVersion(), publishedBy);
        return InstrumentTemplateResponse.of(template);
    }

    /** The version in force of each instrument that has one. */
    @Transactional(readOnly = true)
    public List<InstrumentTemplateResponse> current() {
        return Arrays.stream(InstrumentType.values())
                .map(this::currentTemplate)
                .flatMap(Optional::stream)
                .map(InstrumentTemplateResponse::of)
                .toList();
    }

    /** Every published version of one instrument, latest first. */
    @Transactional(readOnly = true)
    public List<InstrumentTemplateResponse> versions(InstrumentType instrumentType) {
        return templateRepository.findByInstrumentTypeOrderByVersionDesc(instrumentType).stream()
                .map(InstrumentTemplateResponse::of)
                .toList();
    }

    /** One published version of one instrument. */
    @Transactional(readOnly = true)
    public InstrumentTemplateResponse version(InstrumentType instrumentType, int version) {
        return templateRepository.findByInstrumentTypeAndVersion(instrumentType, version)
                .map(InstrumentTemplateResponse::of)
                .orElseThrow(() -> new NotFoundException(
                        String.format("%s has no version %d", instrumentType, version)));
    }

    /** The version in force, if the instrument has been published. */
    Optional<InstrumentTemplate> currentTemplate(InstrumentType instrumentType) {
        return templateRepository.findFirstByInstrumentTypeOrderByVersionDesc(instrumentType);
    }
}

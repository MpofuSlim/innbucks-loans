package zw.co.innbucks.loans.core.instrument;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The terms the Staff Grocery Loan agreement's wording can name, as {@code {{placeholder}}}s, and how they are filled
 * (FR-SGL-026, FR-SGL-027). Its own set, apart from {@link InstrumentTerms}: a staff loan has no tenor, instalment or
 * SSB deduction, and an SSB loan has no repayment date. As there, an unknown name is refused when the wording is
 * published, never left unfilled in an accepted agreement, and amounts are written with two decimals.
 *
 * <p>Every loan is paid in cash into the borrower's InnBucks wallet, and the wording must say where the money goes
 * ({@link #REQUIRED}). The journey refuses an agreement that does not, or that names a term which no longer exists
 * (one written for the withdrawn voucher), so no borrower signs for something they would not get.
 */
public final class StaffLoanTerms {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    /**
     * What a staff loan agreement is filled with: the borrower as the register holds them, and the terms disclosed.
     *
     * @param payoutAccount where the loan is paid, never in full: {@code InnBucks wallet ****3123}
     */
    public record Signing(String borrowerName, String employeeNumber, String nationalIdNumber, String mobileNumber,
                          String department, BigDecimal amount, String currency, BigDecimal interestRate,
                          BigDecimal totalRepayable, LocalDate repaymentDate, String payoutAccount,
                          LocalDate acceptedDate) {
    }

    /** What every agreement must name: where the money is paid. One that does not is never offered for signing. */
    public static final Set<String> REQUIRED = Set.of("payoutAccount");

    private record Term(String description, Function<Signing, String> value) {
    }

    private static final Map<String, Term> TERMS = new LinkedHashMap<>();

    static {
        TERMS.put("borrowerName", new Term("Full name on the staff register", s -> text(s.borrowerName())));
        TERMS.put("employeeNumber", new Term("Employee number", s -> text(s.employeeNumber())));
        TERMS.put("nationalIdNumber", new Term("National ID number", s -> text(s.nationalIdNumber())));
        TERMS.put("mobileNumber", new Term("Mobile number on the staff register", s -> text(s.mobileNumber())));
        TERMS.put("department", new Term("Department", s -> text(s.department())));
        TERMS.put("amount", new Term("Amount borrowed", s -> money(s.amount())));
        TERMS.put("currency", new Term("Currency, e.g. USD", s -> text(s.currency())));
        TERMS.put("interestRate", new Term("Interest rate, percent (0)", s -> rate(s.interestRate())));
        TERMS.put("totalRepayable", new Term("Total to repay", s -> money(s.totalRepayable())));
        TERMS.put("repaymentDate", new Term("Date it is collected from salary, yyyy-MM-dd",
                s -> date(s.repaymentDate())));
        TERMS.put("payoutAccount", new Term("Where the loan is paid, by its last four digits, e.g. InnBucks wallet"
                + " ****3123", s -> text(s.payoutAccount())));
        TERMS.put("acceptedDate", new Term("Date accepted, yyyy-MM-dd", s -> date(s.acceptedDate())));
    }

    private StaffLoanTerms() {
    }

    /** Every placeholder the wording may use, with what it is filled with. */
    public static Map<String, String> placeholders() {
        Map<String, String> placeholders = new LinkedHashMap<>();
        TERMS.forEach((name, term) -> placeholders.put(name, term.description()));
        return Collections.unmodifiableMap(placeholders);
    }

    /** The terms the wording names, in order of first appearance; a name that is not a term is left out. */
    public static Set<String> placeholdersIn(String body) {
        Set<String> used = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body == null ? "" : body);
        while (matcher.find()) {
            if (TERMS.containsKey(matcher.group(1))) {
                used.add(matcher.group(1));
            }
        }
        return Collections.unmodifiableSet(used);
    }

    /** The placeholders in the wording that are not terms, in order of first appearance. */
    public static List<String> unknownPlaceholders(String body) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            if (!TERMS.containsKey(matcher.group(1))) {
                unknown.add(matcher.group(1));
            }
        }
        return List.copyOf(unknown);
    }

    /** The wording with every placeholder filled from these terms. */
    public static String render(String body, Signing signing) {
        Matcher matcher = PLACEHOLDER.matcher(body);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            Term term = TERMS.get(matcher.group(1));
            String value = term == null ? matcher.group() : term.value().apply(signing);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String rate(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }
}

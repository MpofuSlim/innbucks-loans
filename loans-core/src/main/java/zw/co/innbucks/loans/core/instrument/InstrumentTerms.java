package zw.co.innbucks.loans.core.instrument;

import zw.co.innbucks.loans.core.loan.EmploymentDetail;
import zw.co.innbucks.loans.core.loan.Loan;

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
 * The loan terms an instrument's wording can name, as {@code {{placeholder}}}s, and how they are filled
 * (FR-SSB-013). The set is fixed here so that published wording can only name a term the loan actually
 * carries: an unknown name is refused when the wording is published, never left unfilled in a signed
 * instrument. Amounts are written with two decimals and no currency, which the wording supplies.
 */
public final class InstrumentTerms {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private record Term(String description, Function<Signing, String> value) {
    }

    /** The loan being signed for and the market day it is signed on. */
    private record Signing(Loan loan, LocalDate signedDate) {
    }

    private static final Map<String, Term> TERMS = new LinkedHashMap<>();

    static {
        TERMS.put("applicantName", new Term("First and last name",
                s -> (text(s.loan().getFirstName()) + " " + text(s.loan().getLastName())).strip()));
        TERMS.put("firstName", new Term("First name", s -> text(s.loan().getFirstName())));
        TERMS.put("lastName", new Term("Last name", s -> text(s.loan().getLastName())));
        TERMS.put("ecNumber", new Term("EC number", s -> text(s.loan().getEcNumber())));
        TERMS.put("nationalIdNumber", new Term("National ID number", s -> text(s.loan().getNationalIdNumber())));
        TERMS.put("mobileNumber", new Term("Mobile number", s -> text(s.loan().getMobileNumber())));
        TERMS.put("walletNumber", new Term("InnBucks wallet the loan is paid into",
                s -> text(s.loan().payoutWalletNumber())));
        TERMS.put("employerName", new Term("Employer", s -> employment(s, EmploymentDetail::getEmployerName)));
        TERMS.put("ministry", new Term("Ministry or department", s -> employment(s, EmploymentDetail::getMinistry)));
        TERMS.put("station", new Term("Station", s -> employment(s, EmploymentDetail::getStation)));
        TERMS.put("employeeNumber", new Term("Employee number",
                s -> employment(s, EmploymentDetail::getEmployeeNumber)));
        TERMS.put("amount", new Term("Amount paid to the applicant", s -> money(s.loan().getDisbursedAmount())));
        TERMS.put("principal", new Term("Amount borrowed", s -> money(s.loan().getPrincipal())));
        TERMS.put("adminFee", new Term("Admin fee", s -> money(s.loan().getFeeAmount())));
        TERMS.put("adminFeeRate", new Term("Admin fee rate, percent", s -> rate(s.loan().getFeeRate())));
        TERMS.put("interestRate", new Term("Monthly interest rate, percent", s -> rate(s.loan().getInterestRate())));
        TERMS.put("interestAmount", new Term("Interest over the loan", s -> money(s.loan().getInterestAmount())));
        TERMS.put("tenor", new Term("Tenor, months", s -> String.valueOf(s.loan().getTenor())));
        TERMS.put("monthlyInstalment", new Term("Monthly instalment", s -> money(s.loan().getMonthlyInstallment())));
        TERMS.put("monthlyDeduction", new Term("Monthly amount SSB is instructed to deduct",
                s -> money(s.loan().getGrossedMonthlyDeduction())));
        TERMS.put("signedDate", new Term("Date signed, yyyy-MM-dd", s -> date(s.signedDate())));
    }

    private InstrumentTerms() {
    }

    /** Every placeholder wording may use, with what it is filled with. */
    public static Map<String, String> placeholders() {
        Map<String, String> placeholders = new LinkedHashMap<>();
        TERMS.forEach((name, term) -> placeholders.put(name, term.description()));
        return Collections.unmodifiableMap(placeholders);
    }

    /** The placeholders in the wording that are not terms, in order of first appearance. */
    static List<String> unknownPlaceholders(String body) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            if (!TERMS.containsKey(matcher.group(1))) {
                unknown.add(matcher.group(1));
            }
        }
        return List.copyOf(unknown);
    }

    /** The wording with every placeholder filled from this loan, as signed on this day. */
    static String render(String body, Loan loan, LocalDate signedDate) {
        Signing signing = new Signing(loan, signedDate);
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

    private static String employment(Signing signing, Function<EmploymentDetail, String> field) {
        EmploymentDetail detail = signing.loan().getEmploymentDetail();
        return detail == null ? "" : text(field.apply(detail));
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

package zw.co.innbucks.loans.core.getmore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loans never puts a full coupon code in a URL: GetMore's access logs would then hold it. The client still offers the
 * owner-requested {@code code eq} / {@code coupon_code eq} searches, so this test is what keeps them unused: outside
 * the store implementations ({@code getmore/live}, {@code getmore/simulator}), no main source in loans-core or
 * loans-api may search coupons or orders by code. Searching by prefix, and sending a code in a request BODY
 * ({@code deleteByCodes}, the PUT), is what loans does instead.
 */
class GetMoreCodeInUrlTest {

    /** {@code GetMoreCouponQuery.byCode(...)}, {@code GetMoreOrderQuery.byCode(...)}, or a static import of either. */
    private static final Pattern BY_CODE = Pattern.compile("\\bbyCode\\s*\\(");
    /** A canonical constructor whose first argument (the code) is anything but a literal null. */
    private static final Pattern CODE_CONSTRUCTOR =
            Pattern.compile("new\\s+GetMore(Coupon|Order)Query\\s*\\(\\s*+(?!null\\s*,)");

    @Test
    @DisplayName("no loans caller searches GetMore coupons or orders by a full code")
    void noCallerPutsACodeInAUrl() {
        Path root = repositoryRoot();
        List<Path> sources = new ArrayList<>();
        for (String module : List.of("loans-core/src/main/java", "loans-api/src/main/java")) {
            Path dir = root.resolve(module);
            assertThat(dir).as("scanned source root").isDirectory();
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(sources).as("the scan found the sources").hasSizeGreaterThan(100);

        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String path = root.relativize(source).toString().replace('\\', '/');
            if (exempt(path)) {
                continue;
            }
            String text = read(source);
            if (putsACodeInAUrl(text)) {
                offenders.add(path);
            }
        }
        assertThat(offenders).as("search GetMore by prefix; a full code goes only in a request body").isEmpty();
    }

    @Test
    @DisplayName("the scan itself catches each way of asking for a code search, and passes the prefix searches")
    void theScanCatchesWhatItShould() {
        assertThat(putsACodeInAUrl("store.searchCoupons(GetMoreCouponQuery.byCode(code, 1, 50));")).isTrue();
        assertThat(putsACodeInAUrl("store.orders(GetMoreOrderQuery.byCode(code, 1, 50));")).isTrue();
        assertThat(putsACodeInAUrl("import static x.GetMoreCouponQuery.byCode; ... byCode (c, 1, 1)")).isTrue();
        assertThat(putsACodeInAUrl("new GetMoreOrderQuery(voucher.code(), null, from, to, 1, 50)")).isTrue();
        assertThat(putsACodeInAUrl("new GetMoreCouponQuery( code, null, 1, 50)")).isTrue();

        assertThat(putsACodeInAUrl("store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 50));")).isFalse();
        assertThat(putsACodeInAUrl("new GetMoreOrderQuery(null, prefix, from, to, 1, 50)")).isFalse();
        assertThat(putsACodeInAUrl("new GetMoreCouponQuery(null, prefix, page, size)")).isFalse();
        assertThat(putsACodeInAUrl("new GetMoreCouponQuery( null , prefix, page, size)")).isFalse();
    }

    static boolean putsACodeInAUrl(String source) {
        boolean namesAQuery = source.contains("GetMoreCouponQuery") || source.contains("GetMoreOrderQuery");
        return (namesAQuery && BY_CODE.matcher(source).find()) || CODE_CONSTRUCTOR.matcher(source).find();
    }

    private static boolean exempt(String path) {
        return path.contains("/core/getmore/live/")
                || path.contains("/core/getmore/simulator/")
                || path.endsWith("/core/getmore/GetMoreCouponQuery.java")
                || path.endsWith("/core/getmore/GetMoreOrderQuery.java");
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Surefire runs in the module's directory; the repository root holds both modules. */
    private static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("loans-core/src/main/java"))
                    && Files.isDirectory(dir.resolve("loans-api/src/main/java"))) {
                return dir;
            }
        }
        throw new IllegalStateException("No repository root above " + Path.of("").toAbsolutePath());
    }
}

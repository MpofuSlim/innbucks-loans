package zw.co.innbucks.loans.core.workflow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Waits measured in wall-clock hours, to one decimal, and the statistics reported over them. */
public final class WaitHours {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private WaitHours() {
    }

    /** Whole and part hours, to one decimal; never negative. */
    public static BigDecimal between(LocalDateTime from, LocalDateTime to) {
        long minutes = Math.max(0, Duration.between(from, to).toMinutes());
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, 1, RoundingMode.HALF_UP);
    }

    public static long within(Collection<BigDecimal> hours, int targetHours) {
        BigDecimal target = BigDecimal.valueOf(targetHours);
        return hours.stream().filter(took -> took.compareTo(target) <= 0).count();
    }

    /** {@code part} as a share of {@code whole}, to one decimal; null when there is nothing to share. */
    public static BigDecimal percent(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part).multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    public static BigDecimal average(Collection<BigDecimal> hours) {
        if (hours.isEmpty()) {
            return null;
        }
        return hours.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(hours.size()), 1, RoundingMode.HALF_UP);
    }

    public static BigDecimal median(Collection<BigDecimal> hours) {
        if (hours.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = hours.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle)).divide(TWO, 1, RoundingMode.HALF_UP);
    }

    public static BigDecimal longest(Collection<BigDecimal> hours) {
        return hours.stream().max(BigDecimal::compareTo).orElse(null);
    }
}

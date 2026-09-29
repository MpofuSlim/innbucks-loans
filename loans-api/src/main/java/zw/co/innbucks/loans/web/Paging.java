package zw.co.innbucks.loans.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** The page request every paged endpoint builds: zero-based, 20 by default, never more than 100. */
public final class Paging {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    /** Out-of-range values are brought into range rather than refused: a wrong page shows a wrong slice, nothing worse. */
    public static Pageable of(Integer page, Integer size) {
        int pageIndex = page == null ? 0 : Math.max(page, 0);
        int pageSize = size == null ? DEFAULT_SIZE : Math.clamp(size, 1, MAX_SIZE);
        return PageRequest.of(pageIndex, pageSize);
    }
}

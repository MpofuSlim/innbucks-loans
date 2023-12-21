package zw.co.reikan.loans.core.loan;

public enum Title {
    MR("Mr."),
    MRS("Mrs."),
    MISS("Miss"),
    MS("Ms."),
    DR("Dr."),
    PROF("Prof.");

    private final String displayName;

    Title(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

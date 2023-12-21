package zw.co.reikan.loans.core.loan;

public enum EducationLevel {

    PRIMARY("Primary School"),

    SECONDARY("Secondary School"),

    O_LEVEL("Ordinary Level"),

    A_LEVEL("Advanced Level"),

    NATIONAL_DIPLOMA("National Diploma"),

    DEGREE("Bachelor's Degree"),

    MASTERS("Master's Degree"),

    DOCTORATE("Doctorate Degree");

    private final String displayName;

    EducationLevel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

}

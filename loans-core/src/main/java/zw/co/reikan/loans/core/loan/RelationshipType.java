package zw.co.reikan.loans.core.loan;

public enum RelationshipType {

    SIBLING("Sibling", "B"),

    DAUGHTER("Daughter", "DA"),
    SPOUSE("Spouse", "S"),
    DAUGHTER_IN_LAW("Daughter in law", "DI"),
    FATHER("Father", "F"),
    FATHER_IN_LAW("Father in law", "FI"),
    GUARDIAN("Guardian", "G"),
    MOTHER("Mother", "M"),
    MOTHER_IN_LAW("Mother in law", "MI"),

    NEPHEW("Nephew", "NE"),

    SISTER_IN_LAW("Sister in law", "SI"),

    SON("Son", "S");


    private final String displayName;
    private final String code;

    RelationshipType(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }
}

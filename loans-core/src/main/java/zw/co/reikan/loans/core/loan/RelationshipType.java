package zw.co.reikan.loans.core.loan;

public enum RelationshipType {
    SPOUSE("Spouse"),
    PARTNER("Partner"),
    PARENT("Parent"),
    CHILD("Child"),
    SIBLING("Sibling"),
    FRIEND("Friend"),
    COLLEAGUE("Colleague"),
    RELATIVE("Relative"),
    OTHER("Other");

    private final String displayName;

    RelationshipType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

package zw.co.reikan.loans.core.loan;

public enum LineOfBusiness {
    AGRICULTURE("AG", "Agriculture"),
    ARTS_AND_ENTERTAINMENT("AT", "Arts and Entertainment"),
    CONSTRUCTION("CN", "Construction"),
    EDUCATION("ED", "Education"),
    ENERGY("EY", "Energy"),
    FINANCIAL_SERVICES("FS", "Financial Services"),
    GOVERNMENT("GN", "Government"),
    HEALTH("HT", "Health"),
    ICT("ICT", "ICT"),
    MANUFACTURING("MF", "Manufacturing"),
    MINING_AND_QUARRYING_ACTIVITIES("MQ", "Mining and Quarrying Activities"),
    SERVICES("SS", "Services"),
    TOURISM_AND_HOSPITALITY("TH", "Tourism and Hospitality"),
    TRANSPORT_AND_STORAGE("TS", "Transport and Storage"),
    WHOLESALE_AND_RETAIL("WR", "Wholesale and Retail");

    private final String codeId;
    private final String description;

    LineOfBusiness(String codeId, String description) {
        this.codeId = codeId;
        this.description = description;
    }

    public String getCodeId() {
        return codeId;
    }

    public String getDescription() {
        return description;
    }
}
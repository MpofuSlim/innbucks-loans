package zw.co.innbucks.loans.web;

/**
 * Where the API lives. Every controller maps under {@link #BASE}, so the version is changed in one
 * place: a breaking change to the contract ships as {@code /lending/v2} beside this one, never as an
 * edit to it.
 */
public final class ApiPaths {

    public static final String BASE = "/lending/v1";

    private ApiPaths() {
    }
}

package zw.co.innbucks.loans.core.document;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One or more uploaded documents were not accepted. Carries every problem found, so an applicant with two
 * bad uploads hears about both at once rather than one per attempt.
 */
public class DocumentRejectedException extends RuntimeException {

    private final transient List<DocumentProblem> problems;

    public DocumentRejectedException(List<DocumentProblem> problems) {
        super(problems.stream().map(DocumentProblem::message).collect(Collectors.joining(" ")));
        this.problems = List.copyOf(problems);
    }

    public List<DocumentProblem> getProblems() {
        return problems;
    }
}

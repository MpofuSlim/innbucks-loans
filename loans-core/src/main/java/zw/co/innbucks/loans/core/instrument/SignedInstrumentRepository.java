package zw.co.innbucks.loans.core.instrument;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SignedInstrumentRepository extends JpaRepository<SignedInstrument, Long> {

    List<SignedInstrument> findByLoanIdOrderByInstrumentType(Long loanId);
}

package zw.co.innbucks.loans.core.draft;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface LoanApplicationDraftRepository extends JpaRepository<LoanApplicationDraft, Long> {

    /** The draft, locked: two saves of one draft apply one after the other, and it is submitted once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from LoanApplicationDraft d where d.id = :id")
    Optional<LoanApplicationDraft> findByIdForUpdate(@Param("id") Long id);

    Page<LoanApplicationDraft> findByOwnerUserIdAndStatusAndUpdatedAtGreaterThanEqual(
            Long ownerUserId, LoanApplicationDraftStatus status, LocalDateTime updatedSince, Pageable pageable);

    /** Open drafts last saved before the cutoff; their documents go with them (ON DELETE CASCADE). */
    @Modifying
    @Query("""
            delete from LoanApplicationDraft d
            where d.status = zw.co.innbucks.loans.core.draft.LoanApplicationDraftStatus.OPEN
              and d.updatedAt < :cutoff
            """)
    int deleteOpenLastSavedBefore(@Param("cutoff") LocalDateTime cutoff);
}

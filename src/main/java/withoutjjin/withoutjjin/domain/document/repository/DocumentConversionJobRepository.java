package withoutjjin.withoutjjin.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;

import java.util.Optional;

public interface DocumentConversionJobRepository extends JpaRepository<DocumentConversionJob, Long> {

    Optional<DocumentConversionJob> findByDocumentVersionId(Long documentVersionId);
}

package withoutjjin.withoutjjin.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    Optional<DocumentVersion> findByDocumentIdAndVersionNumber(
            Long documentId, Integer versionNumber
    );

    List<DocumentVersion> findAllByDocumentIdOrderByVersionNumberDesc(
            Long documentId
    );

}

package withoutjjin.withoutjjin.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;

import java.util.List;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    List<DocumentVersion> findAllByDocumentIdOrderByVersionNumberDesc(
            Long documentId
    );

}

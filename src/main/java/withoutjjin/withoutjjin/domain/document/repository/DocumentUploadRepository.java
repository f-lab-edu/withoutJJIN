package withoutjjin.withoutjjin.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;

public interface DocumentUploadRepository extends JpaRepository<DocumentUpload, Long> {
}

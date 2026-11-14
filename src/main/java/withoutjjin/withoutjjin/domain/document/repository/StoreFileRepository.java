package withoutjjin.withoutjjin.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;

public interface StoreFileRepository extends JpaRepository<StoreFile, Long> {
}

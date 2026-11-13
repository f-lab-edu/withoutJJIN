package withoutjjin.withoutjjin.domain.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;

public interface DocumentFeedbackRepository extends JpaRepository<DocumentFeedback, Long> {
}

package withoutjjin.withoutjjin.domain.feedback.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateDocumentFeedbackRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    @DisplayName("1000자까지의 피드백 수정 내용을 허용한다")
    void acceptsContentWithinTheLimit() {
        assertThat(validator.validate(new UpdateDocumentFeedbackRequest("수정한 의견"))).isEmpty();
        assertThat(validator.validate(new UpdateDocumentFeedbackRequest(
                "가".repeat(DocumentFeedback.MAX_CONTENT_LENGTH)))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u3000"})
    @DisplayName("누락되거나 공백뿐인 수정 내용을 거부한다")
    void rejectsBlankContent(String content) {
        assertThat(validator.validate(new UpdateDocumentFeedbackRequest(content)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("content"));
    }

    @Test
    @DisplayName("1000자를 초과하는 수정 내용을 거부한다")
    void rejectsContentOverTheLimit() {
        assertThat(validator.validate(new UpdateDocumentFeedbackRequest(
                "가".repeat(DocumentFeedback.MAX_CONTENT_LENGTH + 1))))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("content"));
    }
}

package withoutjjin.withoutjjin.domain.feedback.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CreateDocumentFeedbackRequestTest {

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
    @DisplayName("페이지 전체 영역과 1000자 내용을 등록 요청으로 허용한다")
    void acceptsAValidRequest() {
        assertThat(validator.validate(new CreateDocumentFeedbackRequest(
                1, 0d, 0d, 1d, 1d, "yellow", "가".repeat(1000)))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    @DisplayName("누락되거나 잘못된 피드백 등록 필드를 거부한다")
    void rejectsInvalidFields(CreateDocumentFeedbackRequest request, String field) {
        assertThat(validator.validate(request)).anySatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo(field));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                invalid(null, .1, .1, .2, .2, "green", "내용", "pageNumber"),
                invalid(0, .1, .1, .2, .2, "green", "내용", "pageNumber"),
                invalid(1, null, .1, .2, .2, "green", "내용", "x"),
                invalid(1, -.1, .1, .2, .2, "green", "내용", "x"),
                invalid(1, Double.NaN, .1, .2, .2, "green", "내용", "x"),
                invalid(1, .1, null, .2, .2, "green", "내용", "y"),
                invalid(1, .1, 1.1, .2, .2, "green", "내용", "y"),
                invalid(1, .1, .1, null, .2, "green", "내용", "width"),
                invalid(1, .1, .1, 0d, .2, "green", "내용", "width"),
                invalid(1, .1, .1, Double.POSITIVE_INFINITY, .2, "green", "내용", "width"),
                invalid(1, .1, .1, .2, null, "green", "내용", "height"),
                invalid(1, .1, .1, .2, -.1, "green", "내용", "height"),
                invalid(1, .1, .1, .2, .2, null, "내용", "color"),
                invalid(1, .1, .1, .2, .2, "red", "내용", "color"),
                invalid(1, .1, .1, .2, .2, "GREEN", "내용", "color"),
                invalid(1, .1, .1, .2, .2, "green", null, "content"),
                invalid(1, .1, .1, .2, .2, "green", "\t\n ", "content"),
                invalid(1, .1, .1, .2, .2, "green", "가".repeat(1001), "content")
        );
    }

    private static Arguments invalid(Integer page, Double x, Double y, Double width, Double height,
                                     String color, String content, String field) {
        return Arguments.of(new CreateDocumentFeedbackRequest(page, x, y, width, height, color, content), field);
    }
}

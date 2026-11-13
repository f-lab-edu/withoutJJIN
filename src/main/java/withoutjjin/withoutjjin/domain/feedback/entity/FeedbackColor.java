package withoutjjin.withoutjjin.domain.feedback.entity;

import java.util.Locale;

public enum FeedbackColor {
    GREEN,
    YELLOW,
    BLUE,
    PINK;

    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FeedbackColor fromValue(String value) {
        for (FeedbackColor color : values()) {
            if (color.getValue().equals(value)) return color;
        }
        throw new IllegalArgumentException("지원하지 않는 피드백 영역 색상입니다.");
    }
}

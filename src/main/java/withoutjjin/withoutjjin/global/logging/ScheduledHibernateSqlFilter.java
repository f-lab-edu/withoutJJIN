package withoutjjin.withoutjjin.global.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/** 스케줄러의 반복 SQL 출력만 숨기고 애플리케이션 로그와 경고·오류는 유지한다. */
public class ScheduledHibernateSqlFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return FilterReply.NEUTRAL;
        }
        String logger = event.getLoggerName();
        boolean sqlLog = "org.hibernate.SQL".equals(logger)
                || "org.hibernate.orm.jdbc.bind".equals(logger)
                || "org.hibernate.orm.jdbc.extract".equals(logger);
        String thread = event.getThreadName();
        boolean schedulerThread = thread.startsWith("document-conversion-")
                || thread.startsWith("invitation-email-")
                || thread.startsWith("s3-object-deletion-");
        return sqlLog && schedulerThread ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}

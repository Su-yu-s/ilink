package cn.ilink.teamagent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public final class TeamAgentTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZONE);

    private TeamAgentTime() {}

    public static String iso(Date date) {
        return date == null ? null : ISO.format(date.toInstant());
    }

    public static String iso(Instant instant) {
        return instant == null ? null : ISO.format(instant);
    }
}

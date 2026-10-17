package cn.ilink.service.kg;

import cn.ilink.entity.KgRelation;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A calendar window [startInclusive, endExclusive) extracted from explicit question text. */
public record KgTimeRange(LocalDate startInclusive, LocalDate endExclusive) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalDate UNBOUNDED_END = LocalDate.of(9999, 12, 31);
    private static final Pattern YEAR_MONTH = Pattern.compile("(20\\d{2})年(1[0-2]|0?[1-9])月");
    private static final Pattern MONTH_TOKEN = Pattern.compile("(?:(20\\d{2})年)?(1[0-2]|0?[1-9])月");
    private static final Pattern MONTH_ENUMERATION = Pattern.compile("(?:和|与|及|以及|或|、|，|,)+");
    private static final Pattern MONTH_DAY = Pattern.compile("(1[0-2]|0?[1-9])月(3[01]|[12]?\\d)日");
    private static final Pattern DAY = Pattern.compile("(3[01]|[12]?\\d)日");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)(20\\d{2})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern ISO_MONTH = Pattern.compile("(?<!\\d)(20\\d{2})-(\\d{1,2})(?![-\\d])");

    /** Explicit evaluation date; normal application requests always use the live clock. */
    public static LocalDate today() {
        String fixed = Boolean.getBoolean("kgEval") ? System.getProperty("kgEvalDate") : null;
        return fixed == null ? LocalDate.now(ZONE) : LocalDate.parse(fixed);
    }

    public static Date now() {
        if (Boolean.getBoolean("kgEval") && System.getProperty("kgEvalDate") != null)
            return Date.from(today().atStartOfDay(ZONE).toInstant());
        return new Date();
    }

    public KgTimeRange {
        if (startInclusive == null || endExclusive == null
            || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("invalid time window");
        }
    }

    public static Optional<KgTimeRange> fromQuestion(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String text = question.replaceAll("\\s+", "");
        if (ISO_DATE.matcher(text).find() || ISO_MONTH.matcher(text).find()) return fromIsoQuestion(text);
        Matcher anchor = YEAR_MONTH.matcher(text);
        if (!anchor.find()) {
            return Optional.empty();
        }
        try {
            int year = Integer.parseInt(anchor.group(1));
            int month = Integer.parseInt(anchor.group(2));
            YearMonth firstMonth = YearMonth.of(year, month);
            LocalDate monthStart = firstMonth.atDay(1);
            LocalDate nextMonth = firstMonth.plusMonths(1).atDay(1);

            List<LocalDate> explicitDates = new ArrayList<>();
            Matcher monthDays = MONTH_DAY.matcher(text);
            while (monthDays.find()) {
                int m = Integer.parseInt(monthDays.group(1));
                int d = Integer.parseInt(monthDays.group(2));
                int y = year;
                if (m < month && monthDays.start() > anchor.start()) {
                    y++;
                }
                explicitDates.add(LocalDate.of(y, m, d));
            }
            if (explicitDates.size() >= 2 && text.contains("至")) {
                return range(explicitDates.get(0), explicitDates.get(1).plusDays(1));
            }

            List<Integer> days = new ArrayList<>();
            Matcher dayMatcher = DAY.matcher(text);
            while (dayMatcher.find()) {
                days.add(Integer.parseInt(dayMatcher.group(1)));
            }
            if (explicitDates.isEmpty() && days.size() >= 2 && text.contains("至")) {
                return range(firstMonth.atDay(days.get(0)), firstMonth.atDay(days.get(1)).plusDays(1));
            }
            if (!explicitDates.isEmpty() && (text.contains("以后") || text.contains("之后"))) {
                return range(explicitDates.get(0).plusDays(1), UNBOUNDED_END);
            }
            if (!explicitDates.isEmpty()) {
                return range(explicitDates.get(0), explicitDates.get(0).plusDays(1));
            }
            if (text.contains("中下旬")) {
                return range(firstMonth.atDay(11), nextMonth);
            }
            if (text.contains("上旬")) {
                return range(monthStart, firstMonth.atDay(11));
            }
            if (text.contains("中旬")) {
                return range(firstMonth.atDay(11), firstMonth.atDay(21));
            }
            if (text.contains("下旬")) {
                return range(firstMonth.atDay(21), nextMonth);
            }
            if (text.contains("之后") || text.contains("以后")) {
                return range(nextMonth, UNBOUNDED_END);
            }
            if (text.contains("至今")) {
                return range(monthStart, today().plusDays(1));
            }
            return range(monthStart, nextMonth);
        } catch (DateTimeException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<KgTimeRange> fromIsoQuestion(String text) {
        try {
            List<LocalDate> dates = new ArrayList<>();
            List<Integer> dateStarts = new ArrayList<>();
            List<Integer> dateEnds = new ArrayList<>();
            Matcher day = ISO_DATE.matcher(text);
            while (day.find()) {
                dates.add(LocalDate.of(Integer.parseInt(day.group(1)),
                    Integer.parseInt(day.group(2)), Integer.parseInt(day.group(3))));
                dateStarts.add(day.start());
                dateEnds.add(day.end());
            }
            if (!dates.isEmpty()) {
                LocalDate start = dates.get(0);
                if (dates.size() > 1 && isRangeConnector(text, dateEnds.get(0), dateStarts.get(1)))
                    return range(start, dates.get(1).plusDays(1));
                if (text.contains("以后") || text.contains("之后")) return range(start.plusDays(1), UNBOUNDED_END);
                if (text.contains("至今")) return range(start, today().plusDays(1));
                return range(start, start.plusDays(1));
            }
            List<YearMonth> months = new ArrayList<>();
            List<Integer> monthStarts = new ArrayList<>();
            List<Integer> monthEnds = new ArrayList<>();
            Matcher month = ISO_MONTH.matcher(text);
            while (month.find()) {
                months.add(YearMonth.of(Integer.parseInt(month.group(1)), Integer.parseInt(month.group(2))));
                monthStarts.add(month.start());
                monthEnds.add(month.end());
            }
            if (months.isEmpty()) return Optional.empty();
            YearMonth first = months.get(0);
            if (months.size() > 1 && isRangeConnector(text, monthEnds.get(0), monthStarts.get(1)))
                return range(first.atDay(1), months.get(1).plusMonths(1).atDay(1));
            if (text.contains("以后") || text.contains("之后")) return range(first.plusMonths(1).atDay(1), UNBOUNDED_END);
            if (text.contains("至今")) return range(first.atDay(1), today().plusDays(1));
            return range(first.atDay(1), first.plusMonths(1).atDay(1));
        } catch (DateTimeException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static boolean isRangeConnector(String text, int previousEnd, int nextStart) {
        return text.substring(previousEnd, nextStart).matches("(?:至|到|~|～|—|–|-)+");
    }

    /** Separate calendar months explicitly enumerated in a question, never a date interval. */
    public static List<KgTimeRange> monthWindowsFromQuestion(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        String text = question.replaceAll("\\s+", "");
        Matcher matcher = MONTH_TOKEN.matcher(text);
        List<KgTimeRange> months = new ArrayList<>();
        int year = -1;
        int previousEnd = -1;
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                year = Integer.parseInt(matcher.group(1));
            }
            if (year < 0) {
                continue;
            }
            if (previousEnd >= 0
                && !MONTH_ENUMERATION.matcher(text.substring(previousEnd, matcher.start())).matches()) {
                return List.of();
            }
            YearMonth month = YearMonth.of(year, Integer.parseInt(matcher.group(2)));
            months.add(new KgTimeRange(month.atDay(1), month.plusMonths(1).atDay(1)));
            previousEnd = matcher.end();
        }
        return months.size() < 2 ? List.of() : List.copyOf(months);
    }

    private static Optional<KgTimeRange> range(LocalDate start, LocalDate end) {
        return start.isBefore(end) ? Optional.of(new KgTimeRange(start, end)) : Optional.empty();
    }

    public Date startDate() {
        return Date.from(startInclusive.atStartOfDay(ZONE).toInstant());
    }

    public Date endDate() {
        return Date.from(endExclusive.atStartOfDay(ZONE).toInstant());
    }

    public boolean overlaps(KgRelation edge) {
        Instant start = startDate().toInstant();
        Instant end = endDate().toInstant();
        return (edge.getValidFrom() != null
            && Instant.ofEpochMilli(edge.getValidFrom().getTime()).isBefore(end))
            && (edge.getValidTo() == null
            || Instant.ofEpochMilli(edge.getValidTo().getTime()).isAfter(start));
    }
}

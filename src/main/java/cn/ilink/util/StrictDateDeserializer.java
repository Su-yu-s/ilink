package cn.ilink.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Date;

/**
 * 严格日期反序列化器。
 *
 * <p>背景：Jackson 默认的 {@code Date} 反序列化是宽松的，{@code "2026-13-45"} 这种不存在的日期
 * 会被"滚动"成 2027-02-14 而不报错（iLink 核实报告 P1-2）。团队里程碑的 {@code dueDate}
 * 就因此把错误日期静默落库，团队时间线比用户预期晚几个月且全程无提示。
 *
 * <p>本类把解析收紧到两种明确格式：
 * <ol>
 *   <li>{@code yyyy-MM-dd}（前端日期选择器提交的形态，SMART 严格模式：2026-02-30 直接失败）</li>
 *   <li>ISO-8601 瞬时（带 {@code T} 与偏移/时区，如 {@code 2026-02-14T00:00:00.000Z}）——
 *       兼容历史客户端与既有测试夹具</li>
 * </ol>
 * 其余一律抛 {@link IllegalArgumentException}，由 {@code GlobalExceptionHandler} 转成 400。
 */
public class StrictDateDeserializer extends JsonDeserializer<Date> {

    private static final DateTimeFormatter DATE_ONLY =
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final ZoneId zone;

    public StrictDateDeserializer() {
        this(ZoneId.systemDefault());
    }

    public StrictDateDeserializer(ZoneId zone) {
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
    }

    @Override
    public Date deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw new IllegalArgumentException("日期格式不正确：请使用 yyyy-MM-dd 格式");
        }
        String raw = parser.getText();
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        Date parsed = tryParse(raw.trim());
        if (parsed == null) {
            throw new IllegalArgumentException("日期格式不正确：请使用 yyyy-MM-dd 格式");
        }
        return parsed;
    }

    /**
     * 解析成功返回日期；格式不合法（含 2026-13-45、2026-02-30 这类不存在的日期）返回 null。
     */
    private Date tryParse(String raw) {
        try {
            LocalDate date = LocalDate.parse(raw, DATE_ONLY);
            return Date.from(date.atStartOfDay(zone).toInstant());
        } catch (DateTimeParseException ignored) {
            // 继续尝试 ISO 瞬时格式
        }
        if (raw.indexOf('T') > 0) {
            try {
                return Date.from(Instant.parse(raw));
            } catch (DateTimeParseException ignored) {
                // 两种格式都不匹配
            }
        }
        return null;
    }
}

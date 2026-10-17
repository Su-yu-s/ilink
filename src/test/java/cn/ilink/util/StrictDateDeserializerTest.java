package cn.ilink.util;

import cn.ilink.common.ControllerUtils;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核实报告缺陷修复的纯逻辑单测：
 * P1-2（非法日期必须被拒）、P1-1（邮箱格式口径统一）、P2-1（类型校验工具）。
 */
class StrictDateDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static class Holder {
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = StrictDateDeserializer.class)
        public Date dueDate;
    }

    private Date parse(String json) throws Exception {
        return mapper.readValue(json, Holder.class).dueDate;
    }

    /**
     * 走与生产一致的路径：反序列化器抛出的 {@link IllegalArgumentException} 会被 Jackson
     * 包装成 {@link JsonMappingException}（{@code GlobalExceptionHandler} 据此回 400），
     * 这里拆掉包装以便断言真实原因。
     */
    private void assertRejected(String json) {
        JsonMappingException wrapped = assertThrows(JsonMappingException.class, () -> parse(json));
        assertTrue(wrapped.getCause() instanceof IllegalArgumentException,
            "期望 IllegalArgumentException，实际: " + wrapped.getCause());
        assertTrue(wrapped.getCause().getMessage().contains("日期格式不正确"), wrapped.getCause().getMessage());
    }

    // ---------- P1-2：不存在的日期必须拒绝 ----------

    @Test
    void rejectsNonExistentMonth() {
        assertRejected("{\"dueDate\":\"2026-13-45\"}");
    }

    @Test
    void rejectsNonExistentDay() {
        // 2026 年不是闰年，2 月没有 30 日；宽松解析会滚动到 3 月 2 日
        assertRejected("{\"dueDate\":\"2026-02-30\"}");
        assertRejected("{\"dueDate\":\"2025-02-29\"}");
    }

    @Test
    void rejectsLooseNumericFormat() {
        assertRejected("{\"dueDate\":\"2026-1-5\"}");
    }

    @Test
    void rejectsGarbage() {
        assertRejected("{\"dueDate\":\"not-a-date\"}");
        assertRejected("{\"dueDate\":\"2026/02/14\"}");
    }

    @Test
    void rejectsNonStringToken() {
        // 数字/对象不是字符串 token，Jackson 直接判为格式错误（GlobalExceptionHandler → 400）
        assertThrows(JsonMappingException.class, () -> parse("{\"dueDate\":20260214}"));
        assertThrows(JsonMappingException.class, () -> parse("{\"dueDate\":{}}"));
    }

    // ---------- 合法输入仍然接受 ----------

    @Test
    void acceptsLocalDate() throws Exception {
        Date parsed = parse("{\"dueDate\":\"2026-02-14\"}");
        LocalDate expected = LocalDate.of(2026, 2, 14);
        assertEquals(expected, parsed.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
    }

    @Test
    void acceptsIsoInstantFromLegacyClients() throws Exception {
        Date parsed = parse("{\"dueDate\":\"2026-02-14T00:00:00.000Z\"}");
        assertEquals(Instant.parse("2026-02-14T00:00:00.000Z"), parsed.toInstant());
    }

    @Test
    void acceptsNullAndEmpty() throws Exception {
        assertNull(parse("{\"dueDate\":null}"));
        assertNull(parse("{\"dueDate\":\"\"}"));
    }

    @Test
    void leapDayIsAccepted() throws Exception {
        LocalDate leap = LocalDate.of(2028, 2, 29);
        assertEquals(leap, parse("{\"dueDate\":\"2028-02-29\"}").toInstant()
            .atZone(ZoneId.systemDefault()).toLocalDate());
    }

    // ---------- P1-1：邮箱口径（注册发码与资料更新共用同一套） ----------

    @Test
    void emailFormatCheckAcceptsCommonAddresses() {
        assertNull(ControllerUtils.checkEmailFormat("student@example.com"));
        assertNull(ControllerUtils.checkEmailFormat("a.b+c%_d-e@sub.example.co"));
        assertNull(ControllerUtils.checkEmailFormat(null));
        assertNull(ControllerUtils.checkEmailFormat("   "));
    }

    @Test
    void emailFormatCheckRejectsInvalidValues() {
        assertEquals("邮箱格式不正确", ControllerUtils.checkEmailFormat("not-an-email"));
        assertEquals("邮箱格式不正确", ControllerUtils.checkEmailFormat("a@b"));
        assertEquals("邮箱格式不正确", ControllerUtils.checkEmailFormat("a@@b.com"));
        assertEquals("邮箱格式不正确", ControllerUtils.checkEmailFormat("@example.com"));
        assertEquals("邮箱格式不正确", ControllerUtils.checkEmailFormat("user@example."));
    }

    @Test
    void emailNormalizationTrimsAndLowercases() {
        assertEquals("user@example.com", ControllerUtils.normalizeEmail("  USER@Example.COM  "));
        assertNull(ControllerUtils.normalizeEmail(null));
        assertTrue(ControllerUtils.isValidEmail(ControllerUtils.normalizeEmail("A@B.com")));
        assertFalse(ControllerUtils.isValidEmail(null));
    }

    // ---------- P2-1：非字符串入参必须被拒 ----------

    @Test
    void stringFieldRejectsNonStringValues() {
        assertThrows(IllegalArgumentException.class,
            () -> ControllerUtils.requireStringField(Map.of("content", Map.of("a", 1)), "content", "评论内容"));
        assertThrows(IllegalArgumentException.class,
            () -> ControllerUtils.requireStringField(Map.of("content", 12345), "content", "评论内容"));
        assertThrows(IllegalArgumentException.class,
            () -> ControllerUtils.requireStringField(Map.of("content", true), "content", "评论内容"));
    }

    @Test
    void stringFieldPassesStringsAndMissingValues() {
        assertEquals("正常内容", ControllerUtils.requireStringField(Map.of("content", "正常内容"), "content", "评论内容"));
        assertNull(ControllerUtils.requireStringField(Map.of(), "content", "评论内容"));
        assertNull(ControllerUtils.requireStringField(null, "content", "评论内容"));
    }
}

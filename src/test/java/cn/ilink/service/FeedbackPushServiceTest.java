package cn.ilink.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推送正文的 Markdown 中和，以及日志里的 SendKey 抹除。
 *
 * <p>推送正文是 Markdown，而描述与联系方式完全由提交者控制：
 * 不处理的话，提交者可以塞图片语法做打开追踪、或伪造指向站外的链接钓鱼开发者。
 */
class FeedbackPushServiceTest {

    private FeedbackPushService pushService;

    @BeforeEach
    void setUp() {
        pushService = new FeedbackPushService();
        ReflectionTestUtils.setField(pushService, "sendKey", "SCT417634TOQA88a65SjeJuceiSWHMEwT4");
        // enabled 是 @Value 注入的原始字段，单测里必须显式打开
        ReflectionTestUtils.setField(pushService, "enabled", true);
    }

    @Test
    void fencedCode_neutralisesMarkdownImageAndLinkSyntax() {
        String payload = "正常描述\n![](https://evil.example/track.png)\n[点此领取奖励](https://evil.example/phish)";
        String result = FeedbackPushService.fencedCode(payload);

        assertTrue(result.startsWith("```\n") && result.endsWith("\n```"), "应被包进围栏代码块");
        assertTrue(result.contains("![](https://evil.example/track.png)"), "原文应完整保留（只是失效）");
    }

    @Test
    void fencedCode_growsFenceWhenContentContainsBackticks() {
        String payload = "恶意内容 ``` 提前闭合围栏\n![](https://evil.example/x.png)";

        String result = FeedbackPushService.fencedCode(payload);

        // 围栏必须长于内容里最长的一串反引号，否则内容能提前闭合围栏逃出去
        assertTrue(result.startsWith("````\n"), "围栏应加长到 4 个反引号，实际：" + result.substring(0, 6));
        assertTrue(result.endsWith("\n````"));
    }

    @Test
    void fencedCode_handlesLongBacktickRuns() {
        String payload = "``````` end";

        String result = FeedbackPushService.fencedCode(payload);

        assertTrue(result.startsWith("````````\n"), "围栏应比 7 个反引号更长");
    }

    @Test
    void inlineCode_wrapsPlainValue() {
        assertEquals("`su@example.com`", FeedbackPushService.inlineCode("su@example.com"));
    }

    @Test
    void inlineCode_padsWhenValueStartsOrEndsWithBacktick() {
        // CommonMark：以反引号开头或结尾时必须补空格，否则渲染器不认
        assertEquals("`` `abc ``", FeedbackPushService.inlineCode("`abc"));
        assertEquals("`` `abc` ``", FeedbackPushService.inlineCode("`abc`"));
    }

    @Test
    void scrub_removesSendKeyFromLogMessage() {
        String leaked = "I/O error on POST request for \"https://sctapi.ftqq.com/"
            + "SCT417634TOQA88a65SjeJuceiSWHMEwT4.send\": connection timed out";

        String scrubbed = pushService.scrub(leaked);

        assertFalse(scrubbed.contains("SCT417634TOQA88a65SjeJuceiSWHMEwT4"), "SendKey 不得出现在日志里");
        assertTrue(scrubbed.contains("***"));
        assertTrue(scrubbed.contains("connection timed out"), "其余信息应保留，便于排查");
    }

    @Test
    void scrub_toleratesNullAndMissingKey() {
        assertEquals("", pushService.scrub(null));
        ReflectionTestUtils.setField(pushService, "sendKey", "");
        assertEquals("some message", pushService.scrub("some message"));
    }

    @Test
    void isConfigured_requiresBothSwitchAndKey() {
        assertTrue(pushService.isConfigured());

        ReflectionTestUtils.setField(pushService, "sendKey", "");
        assertFalse(pushService.isConfigured(), "没有 SendKey 时不应尝试推送");

        ReflectionTestUtils.setField(pushService, "sendKey", "SCT123");
        ReflectionTestUtils.setField(pushService, "enabled", false);
        assertFalse(pushService.isConfigured(), "开关关闭时不应尝试推送");
    }
}

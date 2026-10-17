package cn.ilink.service.kg;

import cn.ilink.config.AiProperties;
import cn.ilink.service.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次性诊断（临时文件，不提交）：复现某个索引任务的抽取调用，
 * 把每个窗口的原始模型输出落盘，用于定位「AI 返回的 JSON 无法解析」的根因。
 * 只读数据库，不写任何表。
 *
 * <pre>
 * mvn test -Dtest=KgExtractionDiagTest -DkgDiag=true -DkgDiagTask=72
 * </pre>
 */
class KgExtractionDiagTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "kgDiag", matches = "true")
    void dumpRawExtractionOutputs() throws Exception {
        long taskId = Long.parseLong(System.getProperty("kgDiagTask", "72"));
        Path outDir = Path.of(System.getProperty("kgDiagOut",
            "E:/Code/html/Ilink-worktrees/b3-graphrag-optimization-data/diag")).toAbsolutePath();
        Files.createDirectories(outDir);

        String dbUrl = System.getProperty("kgDiagDbUrl",
            "jdbc:mysql://localhost:3306/ilink_kg_b3_20260923a"
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai");
        String title;
        String content;
        try (Connection c = DriverManager.getConnection(dbUrl, "root", "1234");
             PreparedStatement ps = c.prepareStatement(
                 "SELECT title, content FROM kg_index_task WHERE id = ?")) {
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "任务不存在: " + taskId);
                title = rs.getString(1);
                content = rs.getString(2);
            }
        }

        String cleaned = KgSourceText.clean(content);
        List<String> windows = KgIndexService.extractionWindows(cleaned);
        System.out.printf("[diag] task=%d title=%s rawChars=%d cleanedChars=%d windows=%d%n",
            taskId, title, content.length(), cleaned.length(), windows.size());

        AiClient client = buildClient(2048);
        AiClient wideClient = buildClient(6144);

        for (int i = 0; i < windows.size(); i++) {
            String window = windows.get(i);
            String prompt = KgIndexService.buildExtractionUserPrompt(title, window);
            AiClient.AiChatResult result = client.chat(
                List.of(msg("system", KgIndexService.EXTRACTION_SYSTEM_PROMPT),
                    msg("user", prompt)), 2048);
            StringBuilder report = new StringBuilder();
            report.append("# window ").append(i).append(" length=").append(window.length()).append('\n');
            report.append("# responseChars=").append(result.content == null ? -1 : result.content.length())
                .append(" promptTokens=").append(result.promptTokens)
                .append(" completionTokens=").append(result.completionTokens).append('\n');
            report.append("# parse: ").append(describeParse(result.content)).append('\n');
            report.append("# completionTokens==2048 即输出按上限截断\n");
            if (isMalformed(result.content)) {
                AiClient.AiChatResult retry = wideClient.chat(
                    List.of(msg("system", KgIndexService.EXTRACTION_SYSTEM_PROMPT),
                        msg("user", prompt)), 6144);
                report.append("# retry@6144: responseChars=").append(retry.content == null ? -1 : retry.content.length())
                    .append(" completionTokens=").append(retry.completionTokens)
                    .append(" parse: ").append(describeParse(retry.content)).append('\n');
                Files.writeString(outDir.resolve("retry-w" + i + ".txt"), retry.content, StandardCharsets.UTF_8);
            }
            Files.writeString(outDir.resolve("w" + i + ".txt"), result.content, StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("report-w" + i + ".txt"), report.toString(), StandardCharsets.UTF_8);
            System.out.print(report);
        }
        System.out.println("[diag] 明细已写入 " + outDir);
    }

    private AiClient buildClient(int maxTokens) {
        AiProperties props = new AiProperties();
        props.setApiKey(System.getProperty("kgDiagKey",
            System.getenv().getOrDefault("DASHSCOPE_API_KEY", "")));
        props.setMaxTokens(maxTokens);
        props.setTimeoutMs(120_000);
        return new AiClient(props);
    }

    /** 与 KgIndexService.parseExtraction 相同的定位+解析步骤（只读诊断用）。 */
    private String describeParse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "FAIL 内容为空";
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "FAIL 未找到 JSON 对象";
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            return "OK entities=" + root.path("entities").size()
                + " relations=" + root.path("relations").size();
        } catch (Exception e) {
            return "FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private boolean isMalformed(String raw) {
        return raw == null || raw.trim().isEmpty() || describeParse(raw).startsWith("FAIL");
    }

    private static Map<String, String> msg(String role, String content) {
        return Map.of("role", role, "content", content);
    }
}

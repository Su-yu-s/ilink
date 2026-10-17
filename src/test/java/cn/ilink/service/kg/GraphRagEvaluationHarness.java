package cn.ilink.service.kg;

import java.util.List;
import java.util.Map;

/** Fails a live run if any algorithm's result is missing from a case. */
final class GraphRagEvaluationHarness {
    private GraphRagEvaluationHarness() { }

    static void validateRow(Map<String, Object> row) {
        for (String algorithm : List.of("b1", "b2", "b3")) {
            for (String field : List.of("answer", "citations", "ms", "promptTokens",
                "completionTokens", "pass", "judgeNote", "legacyPass")) {
                String key = algorithm + "_" + field;
                if (!row.containsKey(key)) {
                    throw new IllegalStateException("评测结果缺少字段: " + key
                        + " (case=" + row.get("id") + ")");
                }
            }
            if (!(row.get(algorithm + "_citations") instanceof List)) {
                throw new IllegalStateException("citations 必须保存真实引用列表: " + algorithm);
            }
        }
        if (row.containsKey("b0_answer") || row.containsKey("b0_pass")) {
            throw new IllegalStateException("B0 不能冒充普通 RAG 基线 B1");
        }
    }
}

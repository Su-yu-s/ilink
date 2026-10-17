package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRagEvaluationHarnessTest {
    @Test
    void requiresB1B2B3ForEveryCase() {
        Map<String, Object> row = row();
        assertDoesNotThrow(() -> GraphRagEvaluationHarness.validateRow(row));
        row.remove("b1_answer");
        assertThrows(IllegalStateException.class,
            () -> GraphRagEvaluationHarness.validateRow(row));
    }

    @Test
    void rejectsB0MislabelAndCitationCounts() {
        Map<String, Object> row = row();
        row.put("b0_answer", "纯模型回答");
        assertThrows(IllegalStateException.class,
            () -> GraphRagEvaluationHarness.validateRow(row));
        row.remove("b0_answer");
        row.put("b3_citations", 3);
        assertThrows(IllegalStateException.class,
            () -> GraphRagEvaluationHarness.validateRow(row));
    }

    @Test
    void judgeRejectsUnknownIdEvenWhenSomeCitationExists() {
        GraphRagEvaluationTest.EvalCase evalCase = new GraphRagEvaluationTest.EvalCase(
            "E1", "E", "测试", List.of(), List.of(), false, null);
        List<Map<String, Object>> citations = List.of(Map.of("entityId", 42L, "name", "LitePT"));

        assertFalse(GraphRagEvaluationTest.judge(evalCase,
            "LitePT #42 与编造实体 #999 有关", citations, "B3").pass);
        assertTrue(GraphRagEvaluationTest.judge(evalCase,
            "LitePT #42 可作为检索线索", citations, "B3").pass);
    }

    @Test
    void internalStateRuleAppliesToUnanswerableQuestionsNotQuotedUiText() {
        var fact = new GraphRagEvaluationTest.EvalCase("E11", "E", "空态文字", List.of("未找到相关内容"), List.of(), false, null);
        assertTrue(GraphRagEvaluationTest.judge(fact, "界面显示未找到相关内容", 0).pass);
        var unknown = new GraphRagEvaluationTest.EvalCase("U1", "U", "建议", List.of(), List.of(), true, null);
        assertFalse(GraphRagEvaluationTest.judge(unknown, "无直接关系记录。" + "建议先核实报名条件。".repeat(12), 0).pass);
        assertTrue(GraphRagEvaluationTest.judge(unknown, "平台当前没有直接相关竞赛。" + "建议先核实报名条件。".repeat(12), 0).pass);
    }

    private static Map<String, Object> row() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "E1");
        for (String algorithm : List.of("b1", "b2", "b3")) {
            row.put(algorithm + "_answer", "回答");
            row.put(algorithm + "_citations", List.of());
            row.put(algorithm + "_ms", 1L);
            row.put(algorithm + "_promptTokens", 1);
            row.put(algorithm + "_completionTokens", 1);
            row.put(algorithm + "_pass", false);
            row.put(algorithm + "_judgeNote", "未命中");
            row.put(algorithm + "_legacyPass", false);
        }
        return row;
    }
}

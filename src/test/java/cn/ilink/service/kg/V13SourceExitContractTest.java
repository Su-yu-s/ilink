package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contract tests for v13 deterministic source exits and their negative boundaries. */
class V13SourceExitContractTest {

    @Test
    void sourceRecordListRequiresPositiveAuthorisedHitsAndNeverPublishesAbsence() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.chunks()).thenReturn(List.of(
            chunk(371, "POST", 6, "iLink交流社区文件上传问题修复&&LitePT分享",
                "分享一篇最近的 LitePT 点云论文。"),
            chunk(273, "ASSET", 4, "智能学习助手", "本项目没有 LitePT。")));

        AgenticGraphRagService.AgenticAnswer positive = AgenticGraphRagService.structuredSourceRecordList(
            "知识图谱里哪些帖子记录提到了 LitePT？", ledger, new ArrayList<>(), 0, 0);
        assertNotNull(positive);
        assertTrue(positive.answer.contains("POST:6"));
        assertFalse(positive.answer.contains("ASSET:4"));
        assertTrue(positive.answer.contains("当前授权可见原文"));
        assertNull(AgenticGraphRagService.structuredSourceRecordList(
            "知识图谱里哪些帖子记录提到了 QwenOCR？", ledger, new ArrayList<>(), 0, 0));
    }

    @Test
    void sourceRecordListDoesNotTakeOverOtherQuestionShapes() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.chunks()).thenReturn(List.of(
            chunk(371, "POST", 6, "LitePT 分享", "LitePT 论文。")));
        assertNull(AgenticGraphRagService.structuredSourceRecordList(
            "LitePT 的技术特点是什么？", ledger, new ArrayList<>(), 0, 0));
    }

    @Test
    void sourceHostSummaryRequiresEveryNamedSubjectToHaveAnExplicitHostField() {
        EvidenceLedger complete = mock(EvidenceLedger.class);
        when(complete.chunks()).thenReturn(List.of(
            chunk(291, "COMPETITION", 8, "中国高校计算机大赛（含多个子赛）",
                "竞赛名称：中国高校计算机大赛（含多个子赛）；主办方：教指委等。"),
            chunk(336, "COMPETITION", 53, "中国高校计算机大赛 AIGC 创新赛",
                "主办方：全国高等学校计算机教育研究会；")));
        SourceHostSummary.Result result = SourceHostSummary.build(
            "知识图谱里，中国高校计算机大赛与中国高校计算机大赛 AIGC 创新赛分别由什么组织主办？",
            complete).orElse(null);
        assertNotNull(result);
        assertEquals(2, result.entries().size());
        assertEquals(Set.of(291L, 336L), result.chunkIds());

        EvidenceLedger incomplete = mock(EvidenceLedger.class);
        when(incomplete.chunks()).thenReturn(List.of(
            chunk(291, "COMPETITION", 8, "中国高校计算机大赛", "主办方：教指委等。")));
        assertTrue(SourceHostSummary.build(
            "知识图谱里，中国高校计算机大赛与中国高校计算机大赛 AIGC 创新赛分别由什么组织主办？",
            incomplete).isEmpty(), "A missing child record must remain unresolved");
    }

    @Test
    void sourcePhraseSelectorRejectsUnsupportedTitlesAndAmbiguousSlogans() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.chunks()).thenReturn(List.of(
            chunk(379, "POST", 8, "iLink-平台介绍",
                "iLink — 同声相应 同声相应。频率相近的人，终会在某个路口相遇。"),
            chunk(410, "POST", 9, "其他介绍",
                "其他 — 各有不同 各有不同。无关内容。"),
            chunk(411, "POST", 10, "iLink-平台介绍",
                "iLink — 另一种说法 另一种说法。") 
        ));
        assertTrue(SourcePhraseSelector.select("平台介绍里用哪句话概括 iLink 的理念？", ledger).isEmpty(),
            "Conflicting unique slogan candidates must not be selected");
        EvidenceLedger one = mock(EvidenceLedger.class);
        when(one.chunks()).thenReturn(List.of(
            chunk(379, "POST", 8, "iLink-平台介绍",
                "iLink — 同声相应 同声相应。频率相近的人，终会在某个路口相遇。")));
        SourcePhraseSelector.Selection selection = SourcePhraseSelector.select(
            "平台介绍里用哪句话概括 iLink 的组队理念？", one).orElse(null);
        assertNotNull(selection);
        assertEquals("同声相应", selection.phrase());
    }

    @Test
    void competitionListUsesTheUniqueExplicitListEvenWhenTheSubjectAppearsInOtherRecords() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.chunks()).thenReturn(List.of(
            chunk(261, "ASSET", 1, "ilink",
                "本项目是一款基于 Spring Boot 的高校学生竞赛成果管理平台。"),
            chunk(379, "POST", 8, "iLink-平台介绍",
                "iLink 是一个面向高校师生的竞赛组队与协作平台。"),
            chunk(380, "POST", 8, "iLink-平台介绍",
                "竞赛目录 · 55+ 赛事在手「互联网+」「挑战杯」「数模」等赛事按级别分档。")));
        SourceCompetitionListSelector.Selection selection = SourceCompetitionListSelector.select(
            "知识图谱里 iLink 平台本身（ilink 实体）直接关联了哪些竞赛？", ledger).orElse(null);
        assertNotNull(selection);
        assertEquals(380L, selection.chunkId());
        assertEquals(List.of("互联网+", "挑战杯", "数模"), selection.items());
    }

    @Test
    void competitionListRequiresSeveralItemsAndRejectsConflictingLists() {
        EvidenceLedger oneItem = mock(EvidenceLedger.class);
        when(oneItem.chunks()).thenReturn(List.of(
            chunk(380, "POST", 8, "iLink-平台介绍",
                "竞赛目录 · 只有一个「互联网+」赛事。")));
        assertTrue(SourceCompetitionListSelector.select(
            "iLink 平台直接关联了哪些竞赛？", oneItem).isEmpty());

        EvidenceLedger conflicting = mock(EvidenceLedger.class);
        when(conflicting.chunks()).thenReturn(List.of(
            chunk(380, "POST", 8, "iLink-平台介绍",
                "竞赛目录 · 55+ 赛事在手「互联网+」「挑战杯」「数模」。"),
            chunk(381, "POST", 8, "iLink-平台介绍",
                "竞赛目录 · 55+ 赛事在手「蓝桥杯」「挑战杯」「数模」。")));
        assertTrue(SourceCompetitionListSelector.select(
            "iLink 平台直接关联了哪些竞赛？", conflicting).isEmpty());
    }

    @Test
    void competitionListDoesNotInferUnquotedItemsFromProse() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.chunks()).thenReturn(List.of(
            chunk(380, "POST", 1, "iLink-平台介绍",
                "参与“互联网+”创新创业大赛、挑战杯、数学建模竞赛。")));

        assertTrue(SourceCompetitionListSelector.select(
            "知识图谱里 iLink 平台本身（ilink 实体）直接关联了哪些竞赛？",
            ledger).isEmpty());
    }

    @Test
    void technologySummaryAcceptsAReleaseSuffixAndReturnsExplicitSourceFields() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.chunks()).thenReturn(List.of(
            chunk(261, "ASSET", 1, "ilink",
                "本项目是一款基于 Spring Boot + MyBatis Plus 构建的高校学生竞赛成果管理平台。"
                    + "技术栈：代码实现到部署上线的完整流程；"
                    + "后端基于 Spring Boot 2.7 + MyBatis Plus + MySQL，"
                    + "前端使用 Thymeleaf 模板引擎，并集成了 Spring Security 进行权限控制。"
                    + "成果上传与静态资源存储方案设计（本地存储 + Nginx 代理）。")));
        SourceTechnologySummary.Result result = SourceTechnologySummary.build(
            "「高校学生竞赛成果管理平台」这条成果记录在图谱里关联了哪些技术栈？",
            ledger).orElse(null);
        assertNotNull(result);
        assertEquals(261L, result.chunkId());
        assertTrue(result.technologies().containsAll(
            Set.of("Spring Boot 2.7", "MyBatis Plus", "Thymeleaf", "Spring Security", "Nginx")),
            result.technologies().toString());
        assertTrue(result.text().contains("c#261"));
        assertFalse(result.text().contains("代码实现到部署上线"));
    }

    @Test
    void technologySummaryRejectsAmbiguousOrUndeclaredTechnologyRecords() {
        EvidenceLedger ambiguous = mock(EvidenceLedger.class);
        when(ambiguous.chunks()).thenReturn(List.of(
            chunk(261, "ASSET", 1, "ilink",
                "高校学生竞赛成果管理平台，后端基于 Spring Boot + MySQL。"),
            chunk(262, "ASSET", 2, "ilink-copy",
                "高校学生竞赛成果管理平台，后端基于 Spring Boot + Redis。")));
        assertTrue(SourceTechnologySummary.build(
            "「高校学生竞赛成果管理平台」这条成果记录在图谱里关联了哪些技术栈？",
            ambiguous).isEmpty());

        EvidenceLedger undeclared = mock(EvidenceLedger.class);
        when(undeclared.chunks()).thenReturn(List.of(
            chunk(261, "ASSET", 1, "ilink",
                "高校学生竞赛成果管理平台用于整理作品，没有声明技术栈。")));
        assertTrue(SourceTechnologySummary.build(
            "「高校学生竞赛成果管理平台」这条成果记录在图谱里关联了哪些技术栈？",
            undeclared).isEmpty());
    }

    private static EvidenceLedger.ChunkFact chunk(long id, String type, long docId,
                                                   String title, String text) {
        return new EvidenceLedger.ChunkFact(id, type, docId, title, text, 0, text.length());
    }
}

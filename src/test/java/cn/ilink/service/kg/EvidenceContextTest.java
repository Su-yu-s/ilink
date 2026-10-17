package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceContextTest {
    @Test void relevantTailSurvivesBudgetWithoutCuttingSourceSentences() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        String sentence = "这是另外一种功能的说明，没有涉及页面布局。";
        String source = sentence.repeat(500) + "看板列宽为300px。";
        when(ledger.chunks()).thenReturn(List.of(new EvidenceLedger.ChunkFact(1L, "POST", 1L,
            "界面说明", source, 0, source.length())));
        when(ledger.entities()).thenReturn(List.of());
        when(ledger.relations()).thenReturn(List.of());
        var result = EvidenceContext.select("看板列宽是多少", ledger);
        assertTrue(result.text().contains("看板列宽为300px。"));
        assertTrue(result.text().contains("段内偏移="));
        assertTrue(result.tokenCount() <= EvidenceContext.MAX_BUDGET);
        assertTrue(result.selected() < result.available());
        assertTrue(result.text().endsWith("。\n"));
    }
    @Test void wholeChunkKeepsRelatedListItemsWithoutRepeatedQueryKeywords() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        String source = "故障有两个根因。第一项是字段格式不匹配。第二项是空数组变成null。";
        when(ledger.chunks()).thenReturn(List.of(new EvidenceLedger.ChunkFact(2L, "POST", 2L,
            "问题说明", source, 0, source.length())));
        when(ledger.entities()).thenReturn(List.of());
        when(ledger.relations()).thenReturn(List.of());
        var result = EvidenceContext.select("故障根因有哪些", ledger);
        assertTrue(result.text().contains(source));
        assertTrue(result.tokenCount() <= EvidenceContext.DEFAULT_BUDGET);
    }

    /** E15 的真实片段：通用二元组打不出分，但它是唯一能回答“子目录规则”的证据。 */
    @Test void pathSlotKeepsUploadDirectoryRuleAheadOfGenericChunks() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        String noise = "务、按周生成周报。隐私边界写在界面上——仅发送问题与公开竞赛信息，不泄露聊天记录。";
        String target = "按日期自动创建子目录（{bizType}/yyyy/MM/dd/）";
        when(ledger.chunks()).thenReturn(List.of(
            new EvidenceLedger.ChunkFact(381L, "POST", 8L, "iLink-平台介绍", noise, 0, noise.length()),
            new EvidenceLedger.ChunkFact(339L, "POST", 2L, "ilink开发日志6.22", target, 0, target.length())));
        when(ledger.entities()).thenReturn(List.of());
        when(ledger.relations()).thenReturn(List.of());
        var result = EvidenceContext.select("iLink 上传目录的子目录按什么规则组织？", ledger);
        assertTrue(result.text().contains(target));
    }

    /** E25 的真实片段：目标块标题与主体无关，只有尺寸槽能把它留下。 */
    @Test void sizeSlotKeepsNavigationHeightAheadOfSubjectTitleChunks() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        String noise = "今天把团队空间模块的前端框架和核心交互全部跑通了，模块导航结构重新设计。";
        String target = "顶部全局导航 .il-header 全程保留原有逻辑未修改；桌面端(1440/1024)固定高度72px，"
            + "移动端375px沿用原有响应式56px。";
        when(ledger.chunks()).thenReturn(List.of(
            new EvidenceLedger.ChunkFact(341L, "POST", 3L, "Link 团队空间模块开发日志", noise, 0, noise.length()),
            new EvidenceLedger.ChunkFact(264L, "ASSET", 3L, "前端页面视觉重构升级报告", target, 0, target.length())));
        when(ledger.entities()).thenReturn(List.of());
        when(ledger.relations()).thenReturn(List.of());
        var result = EvidenceContext.select("iLink 顶部全局导航在桌面端和移动端的高度分别是多少？", ledger);
        assertTrue(result.text().contains("72px"));
        assertTrue(result.text().contains("56px"));
    }

    /** E20 的真实片段：颜色槽。 */
    @Test void colorSlotKeepsStyleTokensAheadOfGenericModuleDescription() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        String noise = "团队空间是 iLink 竞赛协作平台的核心功能模块，为已组队成员提供项目级协作环境。";
        String target = "团队空间采用简白风格：背景 #fafafa，卡片 #ffffff，边框 #e5e5e5";
        when(ledger.chunks()).thenReturn(List.of(
            new EvidenceLedger.ChunkFact(341L, "POST", 3L, "Link 团队空间模块开发日志", noise, 0, noise.length()),
            new EvidenceLedger.ChunkFact(262L, "ASSET", 2L, "iLink 团队空间模块开发成果", target, 0, target.length())));
        when(ledger.entities()).thenReturn(List.of());
        when(ledger.relations()).thenReturn(List.of());
        var result = EvidenceContext.select("团队空间简白风格的页面底色和文字色分别是什么？", ledger);
        assertTrue(result.text().contains("#fafafa"));
    }
}

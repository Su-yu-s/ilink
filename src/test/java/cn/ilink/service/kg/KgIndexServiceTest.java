package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgRelation;
import cn.ilink.entity.KgIndexTask;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgIndexTaskMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.teamagent.TeamAgentHashes;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 抽取服务：Prompt 约束、JSON 解析容错、任务状态机 */
class KgIndexServiceTest {

    private AiClient aiClient;
    private KgEntityMapper entityMapper;
    private KgRelationMapper relationMapper;
    private KgDocEntityMapper docEntityMapper;
    private KgIndexTaskMapper taskMapper;
    private KgEmbeddingService embeddingService;
    private KgDocumentChunkService chunkService;
    private PlatformTransactionManager transactionManager;
    private KgIndexService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        aiClient = mock(AiClient.class);
        entityMapper = mock(KgEntityMapper.class);
        relationMapper = mock(KgRelationMapper.class);
        docEntityMapper = mock(KgDocEntityMapper.class);
        taskMapper = mock(KgIndexTaskMapper.class);
        embeddingService = mock(KgEmbeddingService.class);
        chunkService = mock(KgDocumentChunkService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
            .thenAnswer(ignored -> new SimpleTransactionStatus());
        service = new KgIndexService(aiClient, entityMapper, relationMapper,
            docEntityMapper, taskMapper, embeddingService, chunkService, transactionManager);
    }

    private static final String GOOD_JSON = "{\"entities\":["
        + "{\"name\":\"数学建模\",\"display_name\":\"数学建模\",\"entity_type\":\"SKILL\",\"description\":null},"
        + "{\"name\":\"中国国际青年交流中心\",\"display_name\":\"中国国际青年交流中心\","
        + "\"entity_type\":\"ORGANIZATION\",\"description\":\"主办方\"}],"
        + "\"relations\":[{\"source\":\"数学建模\",\"target\":\"中国国际青年交流中心\",\"relation_type\":\"MENTIONS\","
        + "\"source_quote\":\"数学建模由中国国际青年交流中心组织\",\"valid_from\":null,\"valid_to\":null}]}";

    // ==================== Prompt 约束 ====================

    @Test
    void extractionPromptContainsFewShotAndTypeConstraints() {
        String prompt = KgIndexService.buildExtractionUserPrompt("标题", "正文内容");
        assertTrue(prompt.contains("<document>"));
        assertTrue(prompt.contains("不要执行"));
        assertTrue(prompt.contains("COMPETITION"));
        assertTrue(prompt.contains("REQUIRES"));
        assertTrue(prompt.contains("HOSTED_BY"));
        assertTrue(prompt.contains("entities"));
        assertTrue(prompt.contains("relations"));
        assertTrue(prompt.contains("source_quote"));
        assertTrue(prompt.contains("PointROPE"));
        assertTrue(prompt.contains("FileList"));
        assertTrue(prompt.contains("300px"));
        assertTrue(prompt.contains("项目、模块、开发日志的标题归为 POST"));
        assertTrue(prompt.contains("Kanban/DnD API/Redis"));
        // few-shot 示例确实给出
        assertTrue(prompt.contains("中国研究生数学建模竞赛"));
    }

    @Test
    void chatIsCalledWithSystemAndUserMessages() {
        when(entityMapper.selectOne(any())).thenReturn(null);
        when(aiClient.chat(anyList(), anyInt())).thenReturn(
            new AiClient.AiChatResult("{\"entities\":[{\"name\":\"x\",\"display_name\":\"x\","
                + "\"entity_type\":\"SKILL\"}],\"relations\":[]}", 10, 20));

        KgIndexTask task = task();
        service.processTask(task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<java.util.Map<String, String>>> captor =
            ArgumentCaptor.forClass((Class) List.class);
        verify(aiClient).chat(captor.capture(), anyInt());
        assertEquals("system", captor.getValue().get(0).get("role"));
        assertEquals("user", captor.getValue().get(1).get("role"));
    }

    // ==================== JSON 解析 ====================

    @Test
    void parseExtractionToleratesCodeFenceAndNoise() {
        KgIndexService.ExtractionResult result = service.parseExtraction(
            "好的，以下是抽取结果：\n```json\n" + GOOD_JSON + "\n```\n希望对你有帮助");
        assertEquals(2, result.entities.size());
        assertEquals(1, result.relations.size());
    }

    @Test
    void parseExtractionSkipsInvalidTypesAndDuplicates() {
        String json = "{\"entities\":["
            + "{\"name\":\"数学建模\",\"display_name\":\"数学建模\",\"entity_type\":\"SKILL\"},"
            + "{\"name\":\"数学建模\",\"display_name\":\"重复\",\"entity_type\":\"SKILL\"},"
            + "{\"name\":\"坏类型\",\"display_name\":\"坏类型\",\"entity_type\":\"MOVIE\"},"
            + "{\"name\":\"\",\"display_name\":\"空名\",\"entity_type\":\"SKILL\"}],"
            + "\"relations\":[{\"source\":\"a\",\"target\":\"b\",\"relation_type\":\"LOVES\"}]}";
        KgIndexService.ExtractionResult result = service.parseExtraction(json);
        assertEquals(1, result.entities.size());
        assertEquals(0, result.relations.size());
    }

    @Test
    void parseExtractionRejectsEmptyEntities() {
        assertThrows(IllegalStateException.class,
            () -> service.parseExtraction("{\"entities\":[],\"relations\":[]}"));
        assertThrows(IllegalStateException.class, () -> service.parseExtraction(null));
        assertThrows(IllegalStateException.class, () -> service.parseExtraction("完全不是 JSON"));
    }

    @Test
    void parseExtractionRepairsStrayQuoteAfterClosedValue() {
        // 复现 diag 实录：值正常闭合后又跟一个游离引号再接 }（Unexpected character '"'）
        String raw = "{\"entities\":[{\"name\":\"MCP\",\"display_name\":\"MCP\","
            + "\"entity_type\":\"SKILL\",\"description\":\"Model Context Protocol标准协议\"\n\"}],"
            + "\"relations\":[]}";
        KgIndexService.ExtractionResult result = service.parseExtraction(raw);
        assertEquals(1, result.entities.size());
        assertEquals("MCP", result.entities.get(0).name);
    }

    @Test
    void parseExtractionLeavesTruncationToWindowRetry() {
        // 截断（数组中途结束）不属于引号类损坏，必须抛给窗口拆分重试，不能静默吞半截数据
        String truncated = "{\"entities\":[{\"name\":\"MCP\",\"display_name\":\"MCP\","
            + "\"entity_type\":\"SKILL\"},{\"name\":\"etcd\"";
        assertThrows(IllegalStateException.class, () -> service.parseExtraction(truncated));
    }

    @Test
    void sourceBindingRejectsFabricatedRelationsAndDates() {
        String content = "PointROPE 调用 FileList，输出宽度为 300px。数学建模由中国国际青年交流中心组织。";
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(81L);
        chunk.setContent(content);
        String raw = GOOD_JSON.replace("数学建模由中国国际青年交流中心组织",
            "数学建模由中国国际青年交流中心组织")
            .replace("\"valid_from\":null", "\"valid_from\":\"2026-03-01\"");
        KgIndexService.ExtractionResult dated = service.parseExtraction(raw);
        KgIndexService.bindSourceFacts(dated, content, List.of(chunk));
        assertTrue(dated.relations.isEmpty(), "原文未出现的日期不得成为关系生效日期");

        KgIndexService.ExtractionResult unsupported = service.parseExtraction(GOOD_JSON);
        KgIndexService.bindSourceFacts(unsupported, "完全无关的正文", List.of(chunk));
        assertTrue(unsupported.relations.isEmpty(), "引文必须出现在原始正文");
    }

    @Test
    void titleAnchoredMentionNeedsAnExactTargetSpanAndCannotBecomeARequirement() {
        String title = "智能学习助手";
        String content = "后端采用 Python 和 Redis。";
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(82L);
        chunk.setContent(content);
        String mentionJson = "{\"entities\":["
            + "{\"name\":\"智能学习助手\",\"entity_type\":\"POST\"},"
            + "{\"name\":\"Python\",\"entity_type\":\"SKILL\"}],"
            + "\"relations\":[{\"source\":\"智能学习助手\",\"target\":\"Python\","
            + "\"relation_type\":\"MENTIONS\",\"source_quote\":\"后端采用 Python 和 Redis\"}]}";
        KgIndexService.ExtractionResult mention = service.parseExtraction(mentionJson);
        KgIndexService.bindSourceFacts(mention, content, List.of(chunk), title);
        assertEquals(1, mention.relations.size());
        assertEquals(82L, mention.relations.get(0).sourceChunkId);

        KgIndexService.ExtractionResult wrongTitle = service.parseExtraction(mentionJson);
        KgIndexService.bindSourceFacts(wrongTitle, content, List.of(chunk), "另一篇文章");
        assertTrue(wrongTitle.relations.isEmpty());

        KgIndexService.ExtractionResult requirement = service.parseExtraction(
            mentionJson.replace("MENTIONS", "REQUIRES"));
        KgIndexService.bindSourceFacts(requirement, content, List.of(chunk), title);
        assertTrue(requirement.relations.isEmpty());
    }

    @Test
    void technicalNamesCreateOnlyCitedTitleMentions() {
        String title = "团队协作模块开发日志";
        String content = "任务协作看板（Kanban）支持拖拽。后端接 DnD API，缓存使用 Redis。";
        KgIndexService.ExtractionResult extraction = service.parseExtraction(
            "{\"entities\":[{\"name\":\"团队协作模块开发日志\",\"entity_type\":\"POST\"}],"
                + "\"relations\":[]}");
        assertEquals(3, KgIndexService.addTechnicalEntities(extraction, content, title));
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(91L);
        chunk.setContent(content);
        KgIndexService.addTitleMentions(extraction, content, List.of(chunk), title);

        assertEquals(List.of("Kanban", "DnD", "Redis"), extraction.entities.stream()
            .filter(entity -> "SKILL".equals(entity.entityType))
            .map(entity -> entity.name).toList());
        assertEquals(3, extraction.relations.size());
        assertTrue(extraction.relations.stream().allMatch(relation ->
            "MENTIONS".equals(relation.relationType)
                && Long.valueOf(91).equals(relation.sourceChunkId)
                && content.contains(relation.sourceQuote)));
    }

    @Test
    void titleMentionsDoNotMatchInsideOtherTechnicalIdentifiers() {
        String title = "接口开发记录";
        String content = "后端使用 JSONL 记录事件。";
        KgIndexService.ExtractionResult extraction = service.parseExtraction(
            "{\"entities\":[{\"name\":\"接口开发记录\",\"entity_type\":\"POST\"},"
                + "{\"name\":\"JS\",\"entity_type\":\"SKILL\"}],\"relations\":[]}");
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(92L);
        chunk.setContent(content);
        KgIndexService.addTitleMentions(extraction, content, List.of(chunk), title);
        assertTrue(extraction.relations.isEmpty());
    }

    @Test
    void technicalRecoveryRejectsGenericCapitalizedWordsAndUnrelatedProse() {
        KgIndexService.ExtractionResult extraction = service.parseExtraction(
            "{\"entities\":[{\"name\":\"团队空间\",\"entity_type\":\"POST\"}],"
                + "\"relations\":[]}");
        String content = "Link 团队空间采用 Inter 无衬线字体，Primary 按钮旁展示 Demo。"
            + " 技术细节：看板采用 Kanban，后端接 DnD API。";
        assertEquals(2, KgIndexService.addTechnicalEntities(extraction, content, "Link 团队空间"));
        assertEquals(List.of("Kanban", "DnD"), extraction.entities.stream()
            .filter(entity -> "SKILL".equals(entity.entityType))
            .map(entity -> entity.name).toList());
    }

    @Test
    void sourceBindingPreservesExplicitDateAndExactTechnicalTokens() {
        String content = "自2026-03-01起，PointROPE 使用 FileList 输出 300px 宽度。";
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(82L);
        chunk.setContent(content);
        String raw = "{\"entities\":["
            + "{\"name\":\"PointROPE\",\"entity_type\":\"SKILL\"},"
            + "{\"name\":\"FileList\",\"entity_type\":\"SKILL\"}],"
            + "\"relations\":[{\"source\":\"PointROPE\",\"target\":\"FileList\","
            + "\"relation_type\":\"RELATED_TO\",\"source_quote\":\"自2026-03-01起，PointROPE 使用 FileList 输出 300px 宽度\","
            + "\"valid_from\":\"2026-03-01\",\"valid_to\":null}]}";
        KgIndexService.ExtractionResult result = service.parseExtraction(raw);
        KgIndexService.bindSourceFacts(result, content, List.of(chunk));
        assertEquals(1, result.relations.size());
        assertEquals(82L, result.relations.get(0).sourceChunkId);
        assertEquals(java.time.LocalDate.of(2026, 3, 1), result.relations.get(0).validFrom);
        assertTrue(result.relations.get(0).sourceQuote.contains("300px"));
    }

    @Test
    void sourceBindingRecoversExactSpanWhenModelParaphrasesQuote() {
        String content = "竞赛名称：全国大学生数学建模竞赛；主办方：中国工业与应用数学学会。";
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(83L);
        chunk.setContent(content);
        String raw = "{\"entities\":["
            + "{\"name\":\"全国大学生数学建模竞赛\",\"entity_type\":\"COMPETITION\"},"
            + "{\"name\":\"中国工业与应用数学学会\",\"entity_type\":\"ORGANIZATION\"}],"
            + "\"relations\":[{\"source\":\"全国大学生数学建模竞赛\","
            + "\"target\":\"中国工业与应用数学学会\",\"relation_type\":\"HOSTED_BY\","
            + "\"source_quote\":\"这个竞赛由学会主办\",\"valid_from\":null,\"valid_to\":null}]}";
        KgIndexService.ExtractionResult result = service.parseExtraction(raw);
        KgIndexService.bindSourceFacts(result, content, List.of(chunk));
        assertEquals(1, result.relations.size());
        assertEquals(content, result.relations.get(0).sourceQuote);
        assertEquals(83L, result.relations.get(0).sourceChunkId);
    }

    // ==================== 名字归一化 ====================

    @Test
    void normalizeNameStripsCaseSpacesAndPunctuation() {
        assertEquals("数学建模", KgIndexService.normalizeName("数学建模"));
        assertEquals("matlab", KgIndexService.normalizeName(" MATLAB "));
        // 只删标点符号本身，括号内的文字保留（名称归一化语义如此）
        assertEquals("全国大学生数学建模竞赛国赛", KgIndexService.normalizeName("全国大学生数学建模竞赛（国赛）！"));
        assertEquals("", KgIndexService.normalizeName(" ！！！"));
    }

    // ==================== 任务状态机 ====================

    @Test
    void processTaskMarksDoneOnSuccess() {
        // 对齐后的 ID 必须直接来自本任务，不能跨 scope 再查同名实体。
        when(entityMapper.selectOne(any())).thenReturn(
            entityWithId(11L, "数学建模", "SKILL"),
            entityWithId(22L, "中国国际青年交流中心", "ORGANIZATION"));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult(GOOD_JSON, 10, 20));
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(501L);
        chunk.setContent("数学建模由中国国际青年交流中心组织");
        when(chunkService.listIndexedByDocument(any())).thenReturn(List.of(chunk));

        KgIndexTask task = task();
        service.processTask(task);

        ArgumentCaptor<KgIndexTask> captor = ArgumentCaptor.forClass(KgIndexTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals(KgIndexTask.DONE, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getFinishedAt());
        ArgumentCaptor<KgRelation> relation = ArgumentCaptor.forClass(KgRelation.class);
        verify(relationMapper).insert(relation.capture());
        assertEquals("数学建模由中国国际青年交流中心组织", relation.getValue().getFactText());
        assertEquals(501L, relation.getValue().getSourceChunkId());
        assertEquals("POST:100", relation.getValue().getSourceDocId());
        assertEquals(null, relation.getValue().getValidFrom());
        verify(docEntityMapper, org.mockito.Mockito.times(2)).insert(any(cn.ilink.entity.KgDocEntity.class));
        verify(chunkService).index(task);
    }

    @Test
    void sameRelationshipFromTwoDocumentsKeepsSeparateProvenance() {
        when(entityMapper.selectOne(any())).thenReturn(
            entityWithId(11L, "数学建模", "SKILL"),
            entityWithId(22L, "中国国际青年交流中心", "ORGANIZATION"),
            entityWithId(11L, "数学建模", "SKILL"),
            entityWithId(22L, "中国国际青年交流中心", "ORGANIZATION"));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult(GOOD_JSON, 10, 20));
        when(chunkService.listIndexedByDocument(any())).thenAnswer(invocation -> {
            KgIndexTask current = invocation.getArgument(0);
            KgDocumentChunk chunk = new KgDocumentChunk();
            chunk.setId(current.getDocId() + 400);
            chunk.setContent(current.getContent());
            return List.of(chunk);
        });
        KgIndexTask first = task();
        KgIndexTask second = task();
        second.setDocId(101L);
        service.processTask(first);
        service.processTask(second);

        ArgumentCaptor<KgRelation> saved = ArgumentCaptor.forClass(KgRelation.class);
        verify(relationMapper, org.mockito.Mockito.times(2)).insert(saved.capture());
        assertEquals("POST:100", saved.getAllValues().get(0).getSourceDocId());
        assertEquals("POST:101", saved.getAllValues().get(1).getSourceDocId());
        assertEquals(500L, saved.getAllValues().get(0).getSourceChunkId());
        assertEquals(501L, saved.getAllValues().get(1).getSourceChunkId());
    }

    @Test
    void processTaskRetriesThenFailsPermanently() {
        when(aiClient.chat(anyList(), anyInt()))
            .thenThrow(new AiClient.AiUnavailableException("AI 返回内容无法解析"));

        KgIndexTask task = task();
        task.setRetryCount(KgIndexService.MAX_RETRY - 1);
        service.processTask(task);

        ArgumentCaptor<KgIndexTask> captor = ArgumentCaptor.forClass(KgIndexTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals(KgIndexTask.FAILED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getErrorMsg());
    }

    @Test
    void processTaskRequeuesOnFirstFailure() {
        when(aiClient.chat(anyList(), anyInt()))
            .thenThrow(new AiClient.AiUnavailableException("AI 服务连接超时"));

        KgIndexTask task = task();
        service.processTask(task);

        ArgumentCaptor<KgIndexTask> captor = ArgumentCaptor.forClass(KgIndexTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals(KgIndexTask.PENDING, captor.getValue().getStatus());
        assertEquals(1, captor.getValue().getRetryCount());
    }

    @Test
    void processTaskTruncatesLongContent() {
        // 超长正文被截断到 MAX_CONTENT_CHARS，且仍然正常抽取
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9999; i++) {
            sb.append("字");
        }
        when(entityMapper.selectOne(any())).thenReturn(entityWithId(1L, "数学建模", "SKILL"));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult(GOOD_JSON, 10, 20));

        KgIndexTask task = task();
        task.setContent(sb.toString());
        service.processTask(task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<java.util.Map<String, String>>> captor =
            ArgumentCaptor.forClass((Class) List.class);
        verify(aiClient).chat(captor.capture(), anyInt());
        String userPrompt = captor.getValue().get(1).get("content");
        assertFalse(userPrompt.length() > KgIndexService.MAX_CONTENT_CHARS + 2000);
    }

    @Test
    void extractionWindowsCoverTheEndOfLongDocuments() {
        String early = "数学建模由中国国际青年交流中心组织";
        String late = "智能学习助手需要掌握Python";
        KgIndexTask task = task();
        task.setContent(early + "填".repeat(4000) + late);
        KgDocumentChunk first = new KgDocumentChunk();
        first.setId(501L);
        first.setContent(early);
        KgDocumentChunk last = new KgDocumentChunk();
        last.setId(502L);
        last.setContent(late);
        when(chunkService.listIndexedByDocument(task)).thenReturn(List.of(first, last));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(
            new AiClient.AiChatResult(GOOD_JSON, 10, 20),
            new AiClient.AiChatResult("{\"entities\":["
                + "{\"name\":\"智能学习助手\",\"entity_type\":\"POST\"},"
                + "{\"name\":\"Python\",\"entity_type\":\"SKILL\"}],"
                + "\"relations\":[{\"source\":\"智能学习助手\",\"target\":\"Python\","
                + "\"relation_type\":\"REQUIRES\",\"source_quote\":\"智能学习助手需要掌握Python\"}]}"
                , 10, 20));
        when(entityMapper.selectOne(any())).thenReturn(
            entityWithId(11L, "数学建模", "SKILL"),
            entityWithId(12L, "中国国际青年交流中心", "ORGANIZATION"),
            entityWithId(13L, "智能学习助手", "POST"),
            entityWithId(14L, "Python", "SKILL"));

        service.processTask(task);

        ArgumentCaptor<KgRelation> relations = ArgumentCaptor.forClass(KgRelation.class);
        verify(relationMapper, org.mockito.Mockito.times(2)).insert(relations.capture());
        assertEquals("智能学习助手需要掌握Python", relations.getAllValues().get(1).getFactText());
        assertEquals(502L, relations.getAllValues().get(1).getSourceChunkId());
        assertEquals(KgIndexTask.DONE, task.getStatus());
    }

    @Test
    void malformedLargeWindowIsRetriedOnSmallerOverlappingWindows() {
        KgIndexTask task = task();
        task.setContent("数学建模由中国国际青年交流中心组织。".repeat(75));
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(701L);
        chunk.setContent(task.getContent());
        when(chunkService.listIndexedByDocument(task)).thenReturn(List.of(chunk));
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("{\"entities\":[", 10, 20),
                new AiClient.AiChatResult(GOOD_JSON, 10, 20),
                new AiClient.AiChatResult(GOOD_JSON, 10, 20));
        when(entityMapper.selectOne(any())).thenReturn(
            entityWithId(11L, "数学建模", "SKILL"),
            entityWithId(12L, "中国国际青年交流中心", "ORGANIZATION"));

        service.processTask(task);

        assertEquals(KgIndexTask.DONE, task.getStatus());
        verify(aiClient, org.mockito.Mockito.times(3)).chat(anyList(), anyInt());
        verify(relationMapper).insert(any(KgRelation.class));
    }

    @Test
    void globalGraphIdentityDoesNotIncludeAuthorOrTeam() {
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult(GOOD_JSON, 10, 20));
        when(entityMapper.selectOne(any())).thenReturn(
            entityWithId(11L, "数学建模", "SKILL"),
            entityWithId(12L, "中国国际青年交流中心", "ORGANIZATION"));
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(501L);
        chunk.setContent(task().getContent());
        when(chunkService.listIndexedByDocument(any())).thenReturn(List.of(chunk));
        KgIndexTask task = task();
        task.setScope(KgEntity.SCOPE_GLOBAL);
        task.setTeamId(7L);
        task.setUserId(25L);

        service.processTask(task);

        ArgumentCaptor<KgRelation> relation = ArgumentCaptor.forClass(KgRelation.class);
        verify(relationMapper).insert(relation.capture());
        assertEquals(0L, relation.getValue().getTeamId());
        assertEquals(0L, relation.getValue().getUserId());
    }

    // ==================== 入队幂等 ====================

    @Test
    @SuppressWarnings("unchecked")
    void enqueueSkipsDuplicateDoc() throws Exception {
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(new KgIndexTask());
        assertFalse(service.enqueue("POST", 1L, "标题", "正文", "GLOBAL", 0L, 0L));
        verify(taskMapper, never()).insert(any(KgIndexTask.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueueSkipsUnchangedRevisionWithoutResettingLease() {
        KgIndexTask existing = new KgIndexTask();
        existing.setId(9L);
        existing.setTitle("标题");
        existing.setOperation(KgIndexTask.OPERATION_UPSERT);
        existing.setSourceHash(TeamAgentHashes.contentHash("kg-source", "正文"));
        existing.setScope(KgEntity.SCOPE_GLOBAL);
        existing.setTeamId(0L);
        existing.setUserId(0L);
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        assertFalse(service.enqueue("POST", 1L, "标题", "正文", "GLOBAL", 0L, 0L));

        verify(taskMapper, never()).update(any(), any());
        verify(taskMapper, never()).insert(any(KgIndexTask.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueueInsertsPendingTask() throws Exception {
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertTrue(service.enqueue("POST", 1L, "标题", "正文", "TEAM", 3L, 5L));
        ArgumentCaptor<KgIndexTask> captor = ArgumentCaptor.forClass(KgIndexTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(KgIndexTask.PENDING, captor.getValue().getStatus());
        assertEquals(3L, captor.getValue().getTeamId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueueRetriesWhenAWorkerClaimsTheRevisionBetweenReadAndMerge() {
        KgIndexTask existing = new KgIndexTask();
        existing.setId(9L);
        existing.setTitle("旧标题");
        existing.setOperation(KgIndexTask.OPERATION_UPSERT);
        existing.setSourceHash(TeamAgentHashes.contentHash("kg-source", "旧正文"));
        existing.setScope(KgEntity.SCOPE_GLOBAL);
        existing.setTeamId(0L);
        existing.setUserId(0L);
        existing.setRequestedRevision(3L);
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(taskMapper.update(any(), any())).thenReturn(0, 1);

        assertTrue(service.enqueue("POST", 1L, "新标题", "新正文", "GLOBAL", 0L, 0L));

        verify(taskMapper, times(2)).update(any(), any());
        verify(taskMapper, never()).insert(any(KgIndexTask.class));
    }

    @Test
    void staleWorkerCanNeverPublishGraphData() {
        KgIndexTask task = task();
        task.setLeaseOwner("worker-a");
        task.setLeaseToken("old-token");
        task.setRequestedRevision(2L);
        KgIndexTask current = task();
        current.setLeaseOwner("worker-b");
        current.setLeaseToken("new-token");
        current.setRequestedRevision(2L);
        when(taskMapper.selectById(1L)).thenReturn(current);

        service.processTask(task, "worker-a", "old-token");

        verify(aiClient, never()).chat(anyList(), anyInt());
        verify(chunkService, never()).index(any());
        verify(entityMapper, never()).insert(any(KgEntity.class));
        verify(relationMapper, never()).insert(any(KgRelation.class));
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    void revisionAdvanceDuringPublicationRollsBackTheWorkerTransaction() {
        KgIndexTask task = task();
        task.setOperation(KgIndexTask.OPERATION_DELETE);
        task.setLeaseOwner("worker-a");
        task.setLeaseToken("lease-a");
        task.setRequestedRevision(2L);
        KgIndexTask current = task();
        current.setOperation(KgIndexTask.OPERATION_DELETE);
        current.setLeaseOwner("worker-a");
        current.setLeaseToken("lease-a");
        current.setRequestedRevision(2L);
        KgIndexTask advanced = task();
        advanced.setOperation(KgIndexTask.OPERATION_UPSERT);
        advanced.setLeaseOwner("worker-b");
        advanced.setLeaseToken("lease-b");
        advanced.setRequestedRevision(3L);
        when(taskMapper.selectById(1L)).thenReturn(current, advanced);

        service.processTask(task, "worker-a", "lease-a");

        verify(chunkService).deleteByDocument(task);
        verify(transactionManager).rollback(any());
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    void failedPublicationRollsBackThenWritesRetryStateSeparately() {
        KgIndexTask task = task();
        task.setLeaseOwner("worker-a");
        task.setLeaseToken("lease-a");
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("chunk publish failed"))
            .when(chunkService).index(task);

        service.processTask(task, "worker-a", "lease-a");

        verify(transactionManager).rollback(any());
        verify(taskMapper).update(any(), any());
        assertEquals(1, task.getRetryCount());
        assertEquals("chunk publish failed", task.getErrorMsg());
        verify(aiClient, never()).chat(anyList(), anyInt());
    }

    @Test
    void publicationUsesReadCommittedToAvoidChunkRangeDeadlocks() {
        KgIndexTask task = task();
        task.setLeaseOwner("worker-a");
        task.setLeaseToken("lease-a");
        when(taskMapper.selectById(1L)).thenReturn(task);
        org.mockito.Mockito.doThrow(new IllegalStateException("stop after opening transaction"))
            .when(chunkService).index(task);

        service.processTask(task, "worker-a", "lease-a");

        ArgumentCaptor<TransactionDefinition> definitions =
            ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, org.mockito.Mockito.atLeastOnce())
            .getTransaction(definitions.capture());
        assertTrue(definitions.getAllValues().stream().allMatch(definition ->
            definition.getIsolationLevel() == Connection.TRANSACTION_READ_COMMITTED));
    }

    private static KgIndexTask task() {
        KgIndexTask task = new KgIndexTask();
        task.setId(1L);
        task.setDocType("POST");
        task.setDocId(100L);
        task.setTitle("测试文档");
        task.setContent("数学建模由中国国际青年交流中心组织");
        task.setStatus(KgIndexTask.RUNNING);
        task.setRetryCount(0);
        return task;
    }

    private static KgEntity entityWithId(Long id, String name, String type) {
        KgEntity entity = new KgEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDisplayName(name);
        entity.setEntityType(type);
        entity.setScope(KgEntity.SCOPE_GLOBAL);
        entity.setTeamId(0L);
        entity.setUserId(0L);
        entity.setDocCount(1);
        entity.setStatus(KgEntity.STATUS_ACTIVE);
        return entity;
    }
}

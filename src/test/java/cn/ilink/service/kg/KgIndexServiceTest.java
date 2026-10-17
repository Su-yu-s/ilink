package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgIndexTask;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgIndexTaskMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.ai.AiClient;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        service = new KgIndexService(aiClient, entityMapper, relationMapper,
            docEntityMapper, taskMapper, embeddingService);
    }

    private static final String GOOD_JSON = "{\"entities\":["
        + "{\"name\":\"数学建模\",\"display_name\":\"数学建模\",\"entity_type\":\"SKILL\",\"description\":null},"
        + "{\"name\":\"中国国际青年交流中心\",\"display_name\":\"中国国际青年交流中心\","
        + "\"entity_type\":\"ORGANIZATION\",\"description\":\"主办方\"}],"
        + "\"relations\":[{\"source\":\"数学建模\",\"target\":\"中国国际青年交流中心\",\"relation_type\":\"MENTIONS\"}]}";

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
        // 调用序列：对齐 e1 → 对齐 e2 → 解析 source → 解析 target（对齐阶段命中已有实体，走 docCount 更新）
        when(entityMapper.selectOne(any())).thenReturn(
            entityWithId(11L, "数学建模", "SKILL"),
            entityWithId(22L, "中国国际青年交流中心", "ORGANIZATION"),
            entityWithId(11L, "数学建模", "SKILL"),
            entityWithId(22L, "中国国际青年交流中心", "ORGANIZATION"));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult(GOOD_JSON, 10, 20));

        KgIndexTask task = task();
        service.processTask(task);

        ArgumentCaptor<KgIndexTask> captor = ArgumentCaptor.forClass(KgIndexTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals(KgIndexTask.DONE, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getFinishedAt());
        verify(relationMapper).insert(any(cn.ilink.entity.KgRelation.class));
        verify(docEntityMapper, org.mockito.Mockito.times(2)).insert(any(cn.ilink.entity.KgDocEntity.class));
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
    void enqueueInsertsPendingTask() throws Exception {
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertTrue(service.enqueue("POST", 1L, "标题", "正文", "TEAM", 3L, 5L));
        ArgumentCaptor<KgIndexTask> captor = ArgumentCaptor.forClass(KgIndexTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(KgIndexTask.PENDING, captor.getValue().getStatus());
        assertEquals(3L, captor.getValue().getTeamId());
    }

    private static KgIndexTask task() {
        KgIndexTask task = new KgIndexTask();
        task.setId(1L);
        task.setDocType("POST");
        task.setDocId(100L);
        task.setTitle("测试文档");
        task.setContent("这是一篇关于数学建模的测试正文");
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

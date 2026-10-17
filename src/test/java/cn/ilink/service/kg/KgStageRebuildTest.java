package cn.ilink.service.kg;

import cn.ilink.config.AiProperties;
import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgIndexTask;
import cn.ilink.mapper.KgIndexTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in rebuild against a verified clone; the source schema is never touched. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"dev", "local"})
@ContextConfiguration(initializers = KgStageRebuildTest.StageUrlGuard.class)
@EnabledIfSystemProperty(named = "kgRebuild", matches = "true")
class KgStageRebuildTest {
    public static class StageUrlGuard
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            String expected = System.getProperty("kgStageDatabase");
            String url = System.getProperty("spring.datasource.url");
            if (expected == null || !expected.matches("ilink_kg_b3_[a-zA-Z0-9_]+")
                || url == null || !url.matches("jdbc:mysql://[^/]+/" + expected + "(?:\\?.*)?")) {
                throw new IllegalStateException("必须显式指定独立测试库 URL 与 kgStageDatabase");
            }
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "kg-stage-no-background-writes", Map.of(
                    "app.kg.worker.enabled", false,
                    "app.kg.backfill.enabled", false)));
        }
    }
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KgIndexTaskMapper tasks;
    @Autowired private KgIndexService index;
    @Autowired private KgDocumentChunkService chunks;
    @Autowired private AiProperties ai;

    @Test
    void rebuildStagedGraphFromFrozenTasks() throws Exception {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        String expected = System.getProperty("kgStageDatabase");
        assertTrue(expected != null && expected.startsWith("ilink_kg_b3_"));
        assertEquals(expected, database, "拒绝在业务库或意外数据库重建图谱");
        Path backup = Path.of(System.getProperty("kgBackupFile", "")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(backup) && Files.size(backup) > 100_000,
            "必须先验证源库备份文件");
        assertTrue(ai.isConfigured(), "AI 模型尚未配置，不能清理暂存图谱");
        assertEquals(36L, jdbc.queryForObject(
            "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",
            Long.class));
        List<KgIndexTask> frozen = tasks.selectList(new QueryWrapper<KgIndexTask>()
            .ne("doc_type", "EVAL_FIXTURE")
            .orderByAsc("doc_type", "doc_id"));
        assertFalse(frozen.isEmpty());
        boolean graphOnly = Boolean.getBoolean("kgRebuildGraphOnly");
        if (Boolean.getBoolean("kgRebuildDryRun")) {
            System.out.printf("[staged rebuild] dry run: %d source documents, graphOnly=%s;"
                + " no graph rows cleared%n", frozen.size(), graphOnly);
            return;
        }
        int limit = Integer.getInteger("kgRebuildLimit", frozen.size());
        assertTrue(limit > 0 && limit <= frozen.size());
        List<KgIndexTask> selected = frozen.subList(0, limit);
        long originalChunkCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM kg_document_chunk", Long.class);
        Map<Long, List<Long>> retainedChunkIds = graphOnly ? selected.stream()
            .collect(Collectors.toMap(KgIndexTask::getId, this::assertReusableChunks))
            : Map.of();
        if (graphOnly) {
            assertTrue(retainedChunkIds.values().stream().anyMatch(ids -> !ids.isEmpty()),
                "图谱单独重建需要已建立的原文片段快照");
        }

        List<String> graphTables = List.of("kg_entity_embedding", "kg_doc_entity",
            "kg_relation", "kg_entity", "kg_community");
        for (String table : graphTables) {
            jdbc.update("DELETE FROM " + table);
        }
        if (!graphOnly) {
            jdbc.update("DELETE FROM kg_document_chunk");
        }
        int completed = 0;
        for (KgIndexTask task : selected) {
            for (int attempt = 0; attempt < KgIndexService.MAX_RETRY; attempt++) {
                index.processTask(task);
                if (KgIndexTask.DONE.equals(task.getStatus())) {
                    completed++;
                    break;
                }
            }
            System.out.printf("[staged rebuild] %d/%d %s:%s %s%n", completed,
                selected.size(), task.getDocType(), task.getDocId(), task.getStatus());
        }
        assertEquals(selected.size(), completed, "部分文档未重建成功，暂存图谱不能用于评测");
        if (graphOnly) {
            assertEquals(originalChunkCount, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kg_document_chunk", Long.class),
                "图谱重建期间文档片段数量发生变化");
            for (KgIndexTask task : selected) {
                assertEquals(retainedChunkIds.get(task.getId()), assertReusableChunks(task),
                    "图谱重建期间文档片段 ID 发生变化: " + task.getDocType() + ":" + task.getDocId());
            }
        }
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM kg_document_chunk", Long.class) > 0);
        assertEquals(0L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM kg_relation r LEFT JOIN kg_document_chunk c"
                + " ON c.id=r.source_chunk_id WHERE r.source_chunk_id IS NOT NULL AND c.id IS NULL",
            Long.class));
        System.out.println("[staged rebuild] graph relations from EVAL_FIXTURE were cleared;"
            + " rerun KgTemporalFixtureInstallTest before evaluation");
    }

    /** Fail before clearing the graph if a graph-only run would re-index source chunks. */
    private List<Long> assertReusableChunks(KgIndexTask task) {
        List<KgDocumentChunk> desired = KgDocumentChunkService.split(task);
        List<KgDocumentChunk> retained = chunks.listIndexedByDocument(task);
        String ref = task.getDocType() + ":" + task.getDocId();
        long allDocumentChunks = jdbc.queryForObject(
            "SELECT COUNT(*) FROM kg_document_chunk WHERE doc_type=? AND doc_id=?",
            Long.class, task.getDocType(), task.getDocId());
        assertEquals(retained.size(), allDocumentChunks,
            "存在不属于冻结任务权限范围的原文片段: " + ref);
        assertEquals(desired.size(), retained.size(), "原文片段快照与冻结任务不一致: " + ref);
        for (int i = 0; i < desired.size(); i++) {
            KgDocumentChunk expected = desired.get(i);
            KgDocumentChunk actual = retained.get(i);
            assertTrue(actual.getId() != null, "原文片段缺少 ID: " + ref);
            assertEquals(expected.getOrdinalNo(), actual.getOrdinalNo(), "片段序号变化: " + ref);
            assertEquals(expected.getContent(), actual.getContent(), "片段原文变化: " + ref);
            assertEquals(expected.getContentHash(), actual.getContentHash(), "片段文本变化: " + ref);
            assertEquals(expected.getTitle(), actual.getTitle(), "片段标题变化: " + ref);
            assertEquals(expected.getStartOffset(), actual.getStartOffset(), "片段起点变化: " + ref);
            assertEquals(expected.getEndOffset(), actual.getEndOffset(), "片段终点变化: " + ref);
            assertEquals(expected.getScope(), actual.getScope(), "片段可见范围变化: " + ref);
            assertEquals(expected.getTeamId(), actual.getTeamId(), "片段团队变化: " + ref);
            assertEquals(expected.getUserId(), actual.getUserId(), "片段用户变化: " + ref);
        }
        return retained.stream().map(KgDocumentChunk::getId).toList();
    }
}

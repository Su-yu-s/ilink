package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgIndexTask;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgIndexTaskMapper;
import cn.ilink.mapper.KgRelationMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inserts dated synthetic facts as both readable text and graph edges. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"dev", "local"})
@ContextConfiguration(initializers = KgStageRebuildTest.StageUrlGuard.class)
@EnabledIfSystemProperty(named = "kgInstallFixture", matches = "true")
class KgTemporalFixtureInstallTest {
    @Autowired private KgIndexTaskMapper tasks;
    @Autowired private KgEntityMapper entities;
    @Autowired private KgRelationMapper relations;
    @Autowired private KgDocumentChunkService chunks;
    @Autowired private KgEmbeddingService embeddings;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void installSharedSourceGroundedFixture() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of(
            "docs/评测图表/temporal_fixture_v2.json").toFile());
        assertTrue(root.path("synthetic").asBoolean());
        assertEquals(14, root.path("edges").size());
        assertEquals(System.getProperty("kgStageDatabase"),
            jdbc.queryForObject("SELECT DATABASE()", String.class));
        for (JsonNode spec : root.path("edges")) {
            long fixtureId = spec.path("fixtureId").asLong();
            String sourceText = spec.path("sourceText").asText();
            KgIndexTask task = tasks.selectOne(new QueryWrapper<KgIndexTask>()
                .eq("doc_type", "EVAL_FIXTURE").eq("doc_id", fixtureId).last("LIMIT 1"));
            if (task == null) {
                task = new KgIndexTask();
                task.setDocType("EVAL_FIXTURE");
                task.setDocId(fixtureId);
                task.setTitle("合成时序评测事实 " + fixtureId);
                task.setContent(sourceText);
                task.setScope(KgEntity.SCOPE_GLOBAL);
                task.setTeamId(0L);
                task.setUserId(0L);
                task.setStatus(KgIndexTask.DONE);
                task.setRetryCount(0);
                tasks.insert(task);
            } else {
                assertEquals(sourceText, task.getContent(), "夹具内容不能在重复安装时改变");
            }
            chunks.index(task);
            List<KgDocumentChunk> indexed = chunks.listIndexedByDocument(task);
            assertEquals(1, indexed.size());
            KgDocumentChunk chunk = indexed.get(0);
            assertEquals(sourceText, chunk.getContent());

            KgEntity source = anchor(spec.path("source"));
            KgEntity target = anchor(spec.path("target"));
            String relationType = spec.path("relationType").asText();
            KgRelation current = relations.selectOne(new QueryWrapper<KgRelation>()
                .eq("source_id", source.getId()).eq("target_id", target.getId())
                .eq("relation_type", relationType).eq("source_chunk_id", chunk.getId())
                .last("LIMIT 1"));
            if (current == null) {
                KgRelation relation = new KgRelation();
                relation.setSourceId(source.getId());
                relation.setTargetId(target.getId());
                relation.setRelationType(relationType);
                relation.setFactText(sourceText);
                relation.setSourceDocId("EVAL_FIXTURE:" + fixtureId);
                relation.setSourceChunkId(chunk.getId());
                relation.setValidFrom(day(spec.path("validFrom").asText()));
                relation.setValidTo(spec.path("validToExclusive").isNull() ? null
                    : day(spec.path("validToExclusive").asText()));
                relation.setWeight(1.0);
                relation.setScope(KgEntity.SCOPE_GLOBAL);
                relation.setTeamId(0L);
                relation.setUserId(0L);
                relation.setCreatedAt(new Date());
                relations.insert(relation);
            }
        }
        assertEquals(14L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM kg_relation WHERE source_doc_id LIKE 'EVAL_FIXTURE:%'",
            Long.class));
        assertEquals(0L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM kg_relation r LEFT JOIN kg_document_chunk c"
                + " ON c.id=r.source_chunk_id WHERE r.source_doc_id LIKE 'EVAL_FIXTURE:%'"
                + " AND (c.id IS NULL OR r.fact_text <> c.content)", Long.class));
    }

    private KgEntity anchor(JsonNode spec) {
        String name = spec.path("name").asText();
        String type = spec.path("type").asText();
        KgEntity existing = entities.selectOne(new QueryWrapper<KgEntity>()
            .eq("name", name).eq("entity_type", type)
            .eq("scope", KgEntity.SCOPE_GLOBAL).eq("team_id", 0).eq("user_id", 0)
            .eq("status", KgEntity.STATUS_ACTIVE).last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        KgEntity entity = new KgEntity();
        entity.setName(name);
        entity.setDisplayName(spec.path("displayName").asText());
        entity.setEntityType(type);
        entity.setScope(KgEntity.SCOPE_GLOBAL);
        entity.setTeamId(0L);
        entity.setUserId(0L);
        entity.setDocCount(1);
        entity.setStatus(KgEntity.STATUS_ACTIVE);
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        entities.insert(entity);
        embeddings.embedAndSave(entity.getId(), entity.getDisplayName());
        return entity;
    }

    private static Date day(String text) {
        return Date.from(LocalDate.parse(text).atStartOfDay(KgTimeRange.ZONE).toInstant());
    }
}

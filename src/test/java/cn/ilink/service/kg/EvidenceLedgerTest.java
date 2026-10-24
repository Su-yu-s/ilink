package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceLedgerTest {
    private KgScopeService scopes;
    private KgEntityMapper entities;
    private KgRelationMapper relations;
    private KgDocumentChunkMapper chunks;
    private EvidenceLedger ledger;

    @BeforeEach
    void setUp() {
        scopes = mock(KgScopeService.class);
        entities = mock(KgEntityMapper.class);
        relations = mock(KgRelationMapper.class);
        chunks = mock(KgDocumentChunkMapper.class);
        ledger = new EvidenceLedger(entities, relations, chunks, scopes);
        when(scopes.canSee(any(KgEntity.class), any())).thenAnswer(inv ->
            KgEntity.SCOPE_GLOBAL.equals(((KgEntity) inv.getArgument(0)).getScope()));
        when(scopes.canSee(any(), any(), any(), any())).thenAnswer(inv ->
            KgEntity.SCOPE_GLOBAL.equals(inv.getArgument(0)));
        when(scopes.canSeeChunk(any(KgDocumentChunk.class), any())).thenAnswer(inv ->
            KgEntity.SCOPE_GLOBAL.equals(
                ((KgDocumentChunk) inv.getArgument(0)).getScope()));

        when(entities.selectById(42L)).thenReturn(entity(42, "LitePT 论文", "POST"));
        when(entities.selectById(43L)).thenReturn(entity(43, "点云", "SKILL"));
        KgRelation relation = new KgRelation();
        relation.setId(7L);
        relation.setSourceId(42L);
        relation.setTargetId(43L);
        relation.setRelationType(KgRelation.MENTIONS);
        relation.setScope(KgEntity.SCOPE_GLOBAL);
        when(relations.selectById(7L)).thenReturn(relation);
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(99L);
        chunk.setDocType("POST");
        chunk.setDocId(6L);
        chunk.setScope(KgEntity.SCOPE_GLOBAL);
        chunk.setTitle("论文分享");
        chunk.setContent("LitePT 使用 PointROPE 处理点云。");
        when(chunks.selectById(99L)).thenReturn(chunk);
    }

    @Test
    void ingestsOnlyVerifiedTypedToolFacts() {
        ledger.addToolResult("search_entities", Map.of("entities", List.of(
            Map.of("entityId", 42L, "name", "LitePT 论文"))), 8L);
        ledger.addToolResult("expand_neighbors", Map.of("entityId", 42L, "neighbors", List.of(
            Map.of("relationId", 7L, "sourceId", 42L, "targetId", 43L,
                "sourceName", "LitePT 论文", "targetName", "点云", "relationType", "MENTIONS",
                "entityId", 43L, "name", "点云"))), 8L);
        ledger.addToolResult("get_document", Map.of("entityId", 42L, "chunks", List.of(
            Map.of("chunkId", 99L, "docType", "POST", "docId", 6L,
                "title", "论文分享", "text", "LitePT 使用 PointROPE 处理点云。"))), 8L);

        assertTrue(ledger.containsEntity(42L));
        assertTrue(ledger.containsEntity(43L));
        assertTrue(ledger.containsRelation(7L, 42L, 43L));
        assertTrue(ledger.containsChunk(99L));
        assertEquals("点云", ledger.entity(43L).orElseThrow().name());
    }

    @Test
    void ignoresErrorsRawModelTextAndUnknownIds() {
        ledger.addToolResult("search_entities", "{\"error\":\"NO_RESULTS\"}", 8L);
        ledger.addToolResult("model", "实体 #1001 存在", 8L);
        ledger.addToolResult("search_entities", Map.of("entities", List.of(
            Map.of("entityId", 1001L, "name", "编造的竞赛"))), 8L);

        assertFalse(ledger.containsEntity(1001L));
        assertTrue(ledger.entities().isEmpty());
    }

    @Test
    void temporalRelationBringsItsVerifiedOriginalSourceChunk() {
        KgRelation relation = relations.selectById(7L);
        relation.setSourceDocId("POST:6");
        relation.setSourceChunkId(99L);

        ledger.addToolResult("query_temporal", Map.of("relations", List.of(
            Map.of("relationId", 7L, "sourceId", 42L, "targetId", 43L))), 8L);

        assertTrue(ledger.containsRelation(7L, 42L, 43L));
        assertTrue(ledger.containsChunk(99L));
        assertEquals("LitePT 使用 PointROPE 处理点云。",
            ledger.chunk(99L).orElseThrow().text());
    }

    @Test
    void rejectsInvisibleEntityAndRelationEndpoint() {
        KgEntity privateEntity = entity(100, "私人记录", "POST");
        privateEntity.setScope(KgEntity.SCOPE_PRIVATE);
        when(entities.selectById(100L)).thenReturn(privateEntity);
        KgRelation edge = new KgRelation();
        edge.setId(8L);
        edge.setSourceId(42L);
        edge.setTargetId(100L);
        edge.setScope(KgEntity.SCOPE_GLOBAL);
        when(relations.selectById(8L)).thenReturn(edge);

        ledger.addToolResult("search_entities", Map.of("entities", List.of(
            Map.of("entityId", 100L, "name", "私人记录"))), 8L);
        ledger.addToolResult("query_temporal", Map.of("relations", List.of(
            Map.of("relationId", 8L, "sourceId", 42L, "targetId", 100L))), 8L);

        assertFalse(ledger.containsEntity(100L));
        assertFalse(ledger.containsRelation(8L, 42L, 100L));
    }

    private static KgEntity entity(long id, String name, String type) {
        KgEntity row = new KgEntity();
        row.setId(id);
        row.setDisplayName(name);
        row.setEntityType(type);
        row.setScope(KgEntity.SCOPE_GLOBAL);
        row.setStatus(KgEntity.STATUS_ACTIVE);
        return row;
    }
}

package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.kg.KgDocumentChunkService;
import cn.ilink.service.kg.KgScopeService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReadRelationSourceToolTest {
    private static final Long USER = 8L;
    private static final Long RELATION = 30L;
    private static final Long MEMBER = 20L;

    @Test
    void schemaAcceptsRelationAndMemberRatherThanCallerSuppliedDocument() {
        Fixture f = fixture();
        assertEquals("read_relation_source", f.tool.getName());
        assertTrue(f.tool.getParametersSchema().contains("member_entity_id"));
        assertFalse(f.tool.getParametersSchema().contains("doc_id"));
    }

    @Test
    void invalidIdentifiersNeverReadDatabaseOrSource() {
        Fixture f = fixture();
        assertError(f.tool.execute(null, USER), "INVALID_PARAMS");
        assertError(f.tool.execute(Map.of("relation_id", -1, "member_entity_id", MEMBER), USER), "INVALID_PARAMS");
        assertError(f.tool.execute(Map.of("relation_id", RELATION, "member_entity_id", 0), USER), "INVALID_PARAMS");
        verifyNoInteractions(f.relations, f.entities, f.chunks);
    }

    @Test
    void missingRelationNeverReadsEntitiesOrSource() {
        Fixture f = fixture();
        when(f.relations.selectById(RELATION)).thenReturn(null);
        assertError(execute(f), "NOT_FOUND");
        verifyNoInteractions(f.entities, f.chunks);
    }

    @Test
    void returnedRelationIdentityMustEqualRequestedIdentifier() {
        Fixture f = fixture(); f.relation.setId(31L);
        assertError(execute(f), "INVALID_RELATION_BINDING");
        verifyNoInteractions(f.entities, f.chunks);
    }

    @Test
    void returnedEndpointIdentityMustEqualItsRelationEndpointIdentifier() {
        Fixture wrongSource = fixture(); wrongSource.source.setId(11L);
        assertError(execute(wrongSource), "INVALID_RELATION_BINDING");
        verifyNoInteractions(wrongSource.chunks);
        Fixture wrongTarget = fixture(); wrongTarget.target.setId(21L);
        assertError(execute(wrongTarget), "INVALID_RELATION_BINDING");
        verifyNoInteractions(wrongTarget.chunks);
    }

    @Test
    void privateRelationDoesNotExposeBindingOrReadEntities() {
        Fixture f = fixture();
        f.relation.setScope(KgEntity.SCOPE_PRIVATE); f.relation.setUserId(99L);
        Object result = execute(f);
        assertError(result, "PERMISSION_DENIED");
        assertFalse(result.toString().contains("POST:7"));
        verifyNoInteractions(f.entities, f.chunks);
    }

    @Test
    void memberOutsideRelationEndpointsNeverReadsSource() {
        Fixture f = fixture();
        assertError(f.tool.execute(Map.of("relation_id", RELATION, "member_entity_id", 55L), USER), "MEMBER_ENDPOINT_MISMATCH");
        verifyNoInteractions(f.entities, f.chunks);
    }

    @Test
    void inactiveSourceEndpointNeverReadsSource() {
        Fixture f = fixture(); f.source.setStatus(KgEntity.STATUS_MERGED);
        assertError(execute(f), "PERMISSION_DENIED");
        verifyNoInteractions(f.chunks);
    }

    @Test
    void missingTargetEndpointNeverReadsSource() {
        Fixture f = fixture(); when(f.entities.selectById(MEMBER)).thenReturn(null);
        assertError(execute(f), "PERMISSION_DENIED");
        verifyNoInteractions(f.chunks);
    }

    @Test
    void invisibleSourceEndpointNeverReadsSource() {
        Fixture f = fixture(); when(f.scope.canSee(f.source, USER)).thenReturn(false);
        assertError(execute(f), "PERMISSION_DENIED");
        verifyNoInteractions(f.chunks);
    }

    @Test
    void invisibleTargetEndpointNeverReadsSource() {
        Fixture f = fixture(); when(f.scope.canSee(f.target, USER)).thenReturn(false);
        assertError(execute(f), "PERMISSION_DENIED");
        verifyNoInteractions(f.chunks);
    }

    @Test
    void blankMemberNameCannotBecomeMatchAllQuery() {
        Fixture f = fixture(); f.target.setDisplayName("  ");
        assertError(execute(f), "INVALID_MEMBER_NAME");
        verifyNoInteractions(f.chunks);
    }

    @Test
    void missingSourceIdentityDoesNotGuessFromEntityMappings() {
        Fixture f = fixture(); f.relation.setSourceDocId(null);
        assertError(execute(f), "NO_BOUND_SOURCE_DOCUMENT");
        verifyNoInteractions(f.chunks);
    }

    @Test
    void malformedOrUnknownDocumentIdentityIsRejectedBeforeLookup() {
        for (String invalid : List.of("OTHER:7", "POST:-1", "POST:0", "POST:7:8", "POST:9223372036854775808")) {
            Fixture f = fixture(); f.relation.setSourceDocId(invalid);
            assertError(execute(f), "NO_BOUND_SOURCE_DOCUMENT");
            verifyNoInteractions(f.chunks);
        }
    }

    @Test
    void declaredSourceChunkWithLiteralMatchHasPriorityAndExactCoordinates() {
        Fixture f = fixture();
        KgDocumentChunk first = chunk(1L, 0, "Atlas generic context", 0);
        KgDocumentChunk bound = chunk(97L, 9, "prefix aTlAs runtime", 200);
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(first, bound));
        Map<String, Object> result = result(execute(f));
        assertEquals(List.of(97L, 1L), rows(result, "chunks").stream().map(row -> row.get("chunkId")).toList());
        Map<String, Object> position = rows(rows(result, "chunks").get(0), "memberNameMatches").get(0);
        assertEquals(7, position.get("localStart")); assertEquals(207, position.get("sourceStart"));
        assertEquals(212, position.get("sourceEnd"));
        assertEquals(97L, section(result, "sourceBinding").get("sourceChunkId"));
        assertEquals("Atlas is mentioned in the original source.", section(result, "sourceBinding").get("factText"));
        assertFalse((Boolean) section(result, "sourceSearch").get("semanticSupportEstablished"));
    }

    @Test
    void unmatchedDeclaredChunkDoesNotBlockNewSameDocumentSourceAndReturnLimit() {
        Fixture f = fixture();
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(
            chunk(97L, 0, "unrelated source", 0), chunk(2L, 1, "Atlas first", 100),
            chunk(3L, 2, "Atlas second", 200), chunk(4L, 3, "Atlas third", 300)));
        Map<String, Object> result = result(execute(f));
        assertEquals(List.of(2L, 3L), rows(result, "chunks").stream().map(row -> row.get("chunkId")).toList());
        Map<String, Object> search = section(result, "sourceSearch");
        assertEquals(3, search.get("matchedChunkCount")); assertEquals(2, search.get("returnedChunkCount"));
        assertEquals(false, search.get("allMatchedChunksReturned")); assertEquals(true, search.get("scanComplete"));
        verify(f.chunks).listByDocument("POST", 7L, USER);
    }

    @Test
    void unicodeCaseMatchingDoesNotShiftOriginalOffsets() {
        Fixture f = fixture();
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(chunk(2L, 0, "İ: aTlAs runtime", 50)));
        Map<String, Object> position = rows(rows(result(execute(f)), "chunks").get(0), "memberNameMatches").get(0);
        assertEquals(3, position.get("localStart")); assertEquals(53, position.get("sourceStart"));
    }

    @Test
    void prefixAndSuffixFalseMatchesCannotOccupyTheTwoReturnSlots() {
        Fixture f = fixture();
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(
            chunk(97L, 0, "CloudAtlasClient", 0), chunk(2L, 1, "AtlasSDK", 100),
            chunk(3L, 2, "aTlAs runtime", 200), chunk(4L, 3, "Atlas service", 300)));
        Map<String, Object> result = result(execute(f));
        assertEquals(List.of(3L, 4L), rows(result, "chunks").stream().map(row -> row.get("chunkId")).toList());
        assertEquals(2, section(result, "sourceSearch").get("matchedChunkCount"));
        assertFalse(result.toString().contains("CloudAtlasClient"));
        assertFalse(result.toString().contains("AtlasSDK"));
    }

    @Test
    void technicalTokenContinuationsRespectDotHyphenAndSpecialCharacterBoundaries() {
        Fixture f = fixture();
        String falseMatches = "Atlas.SDK SDK.Atlas Atlas-runtime runtime-Atlas Atlas_cache cache_Atlas Atlas+ Atlas#";
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(
            chunk(97L, 0, falseMatches, 0), chunk(2L, 1, "(ATLAS). Atlas- ", 100)));
        Map<String, Object> result = result(execute(f));
        assertEquals(List.of(2L), rows(result, "chunks").stream().map(row -> row.get("chunkId")).toList());
        assertEquals(2, section(result, "sourceSearch").get("literalHitCount"));
        assertEquals("CASE_INSENSITIVE_BOUNDED_REGION_MATCHES_SOURCE_OFFSETS",
            section(result, "sourceSearch").get("literalMatchPolicy"));
    }

    @Test
    void nonAsciiMemberNamesKeepSourceCoordinatesWithoutAnAsciiBoundaryRestriction() {
        Fixture f = fixture(); f.target.setDisplayName("知识");
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(chunk(2L, 0, "相关知识模块", 100)));
        Map<String, Object> match = rows(rows(result(execute(f)), "chunks").get(0), "memberNameMatches").get(0);
        assertEquals(2, match.get("localStart")); assertEquals(102, match.get("sourceStart"));
        assertEquals(104, match.get("sourceEnd"));
    }

    @Test
    void unauthorizedChunkCannotEnterHitsOrTextEvenWhenServiceReturnsIt() {
        Fixture f = fixture();
        KgDocumentChunk hidden = chunk(2L, 0, "Atlas PRIVATE_SECRET", 50);
        hidden.setScope(KgEntity.SCOPE_PRIVATE); hidden.setUserId(99L); hidden.setTitle("PRIVATE_TITLE");
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(hidden));
        Map<String, Object> result = result(execute(f));
        assertEquals(0, result.get("count")); assertEquals(0, section(result, "sourceSearch").get("visibleChunkCount"));
        assertFalse(result.toString().contains("PRIVATE_SECRET")); assertFalse(result.toString().contains("PRIVATE_TITLE"));
    }

    @Test
    void unrelatedDocumentCannotEnterHitsEvenWhenServiceReturnsIt() {
        Fixture f = fixture();
        KgDocumentChunk wrongId = chunk(2L, 0, "Atlas WRONG_DOCUMENT", 0); wrongId.setDocId(8L);
        KgDocumentChunk wrongType = chunk(3L, 1, "Atlas WRONG_TYPE", 100); wrongType.setDocType("ASSET");
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(wrongId, wrongType));
        Map<String, Object> result = result(execute(f));
        assertEquals(0, result.get("count")); assertFalse(result.toString().contains("WRONG_DOCUMENT"));
        assertFalse(result.toString().contains("WRONG_TYPE"));
    }

    @Test
    void noLiteralHitOnlyDescribesCurrentVisibleSourceScope() {
        Fixture f = fixture();
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(chunk(2L, 0, "unrelated service", 0)));
        Map<String, Object> search = section(result(execute(f)), "sourceSearch");
        assertEquals("NO_MATCH_IN_SCANNED_VISIBLE_SOURCE", search.get("resultReason"));
        assertEquals("VISIBLE_CHUNKS_OF_RELATION_SOURCE_DOCUMENT", search.get("scope"));
        assertEquals(1, search.get("scannedChunkCount")); assertEquals(true, search.get("scanComplete"));
        assertEquals(false, search.get("globalAbsenceEstablished"));
    }

    @Test
    void inconsistentStoredCoordinatesAreReportedWithoutInventedAbsoluteOffsets() {
        Fixture f = fixture(); KgDocumentChunk invalid = chunk(2L, 0, "Atlas", 50); invalid.setEndOffset(99);
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(invalid));
        Map<String, Object> result = result(execute(f));
        Map<String, Object> hit = rows(section(result, "sourceSearch"), "authorizedHits").get(0);
        assertEquals(false, hit.get("coordinatesVerified"));
        assertFalse(rows(hit, "matches").get(0).containsKey("sourceStart"));
        assertEquals(99, rows(result, "chunks").get(0).get("endOffset"));
    }

    @Test
    void displayLimitPreservesSurrogatePairAndMatchAfterDisplayPrefix() {
        Fixture f = fixture(); String content = "前".repeat(799) + "😀 Atlas runtime";
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(chunk(2L, 0, content, 1000)));
        Map<String, Object> row = rows(result(execute(f)), "chunks").get(0);
        assertEquals(799, ((String) row.get("text")).length()); assertEquals(false, row.get("displayTextComplete"));
        Map<String, Object> match = rows(row, "memberNameMatches").get(0);
        assertEquals(802, match.get("localStart")); assertEquals(1802, match.get("sourceStart"));
        assertEquals(1000 + content.length(), row.get("endOffset"));
    }

    @Test
    void boundedScanDoesNotClaimUnscannedVisibleChunksWereChecked() {
        Fixture f = fixture(); List<KgDocumentChunk> source = new ArrayList<>();
        for (int i = 0; i < 130; i++) source.add(chunk(1000L + i, i, i == 129 ? "Atlas outside scan" : "other", i * 100));
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(source);
        Map<String, Object> search = section(result(execute(f)), "sourceSearch");
        assertEquals(130, search.get("visibleChunkCount")); assertEquals(128, search.get("scannedChunkCount"));
        assertEquals(false, search.get("scanComplete")); assertEquals(0, search.get("matchedChunkCount"));
        assertEquals(false, search.get("globalAbsenceEstablished"));
    }

    @Test
    void sourceEndpointIsAlsoEligibleAsRequestedMemberWithoutAssumingDirection() {
        Fixture f = fixture();
        when(f.chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of(chunk(2L, 0, "ProjectRecord context", 0)));
        Map<String, Object> result = result(f.tool.execute(Map.of("relation_id", RELATION, "member_entity_id", 10L), USER));
        assertEquals(1, result.get("count")); assertEquals(10L, section(result, "sourceBinding").get("memberEntityId"));
    }

    private record Fixture(ReadRelationSourceTool tool, KgScopeService scope, KgRelationMapper relations,
                           KgEntityMapper entities, KgDocumentChunkService chunks,
                           KgRelation relation, KgEntity source, KgEntity target) { }

    private static Fixture fixture() {
        KgScopeService scope = mock(KgScopeService.class); KgRelationMapper relations = mock(KgRelationMapper.class);
        KgEntityMapper entities = mock(KgEntityMapper.class); KgDocumentChunkService chunks = mock(KgDocumentChunkService.class);
        KgRelation relation = new KgRelation(); relation.setId(RELATION); relation.setSourceId(10L); relation.setTargetId(MEMBER);
        relation.setScope(KgEntity.SCOPE_GLOBAL); relation.setRelationType(KgRelation.MENTIONS);
        relation.setSourceDocId("POST:7"); relation.setSourceChunkId(97L);
        relation.setFactText("Atlas is mentioned in the original source.");
        KgEntity source = entity(10L, "ProjectRecord"); KgEntity target = entity(MEMBER, "Atlas");
        when(relations.selectById(RELATION)).thenReturn(relation);
        when(entities.selectById(10L)).thenReturn(source); when(entities.selectById(MEMBER)).thenReturn(target);
        when(scope.canSee(any(KgEntity.class), eq(USER))).thenReturn(true);
        when(scope.canSee(eq(KgEntity.SCOPE_GLOBAL), any(), any(), eq(USER))).thenReturn(true);
        when(chunks.listByDocument("POST", 7L, USER)).thenReturn(List.of());
        return new Fixture(new ReadRelationSourceTool(scope, relations, entities, chunks), scope, relations, entities,
            chunks, relation, source, target);
    }

    private static KgEntity entity(Long id, String name) {
        KgEntity entity = new KgEntity(); entity.setId(id); entity.setDisplayName(name);
        entity.setStatus(KgEntity.STATUS_ACTIVE); entity.setScope(KgEntity.SCOPE_GLOBAL); return entity;
    }

    private static KgDocumentChunk chunk(Long id, int ordinal, String content, int offset) {
        KgDocumentChunk chunk = new KgDocumentChunk(); chunk.setId(id); chunk.setDocType("POST"); chunk.setDocId(7L);
        chunk.setOrdinalNo(ordinal); chunk.setScope(KgEntity.SCOPE_GLOBAL); chunk.setTitle("Bound source");
        chunk.setContent(content); chunk.setStartOffset(offset); chunk.setEndOffset(offset + content.length()); return chunk;
    }

    private static Object execute(Fixture fixture) {
        return fixture.tool.execute(Map.of("relation_id", RELATION, "member_entity_id", MEMBER), USER);
    }

    private static void assertError(Object result, String code) { assertTrue(result.toString().contains(code)); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Object value) { return (Map<String, Object>) value; }

    private static Map<String, Object> section(Map<String, Object> value, String name) { return result(value.get(name)); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> value, String name) {
        return (List<Map<String, Object>>) value.get(name);
    }
}

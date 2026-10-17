package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Full, typed evidence from successful graph tools; never built from model prose. */
public class EvidenceLedger {
    public record EntityFact(Long id, String name, String type, String description) { }
    public record RelationFact(Long id, Long sourceId, Long targetId, String type,
                               java.util.Date validFrom, java.util.Date validTo,
                               String sourceDocId, Long sourceChunkId, String factText) { }
    public record ChunkFact(Long id, String docType, Long docId, String title,
                            String text, Integer startOffset, Integer endOffset) { }
    public record ToolCoverage(String tool, Long entityId, String startInclusive,
                               String endExclusive, boolean complete, boolean truncated,
                               Set<Long> relationIds) { }
    private record NeighborPage(Long entityId, int offset, int visible, Set<Long> relationIds,
                                boolean countsComplete, Integer nextOffset, String fingerprint) { }

    private static final Set<String> ALLOWED_TOOLS = Set.of(
        "search_entities", "filter_entities", "expand_neighbors", "query_temporal",
        "get_document", "search_documents", "get_relations", "read_relation_source");

    private final KgEntityMapper entityMapper;
    private final KgRelationMapper relationMapper;
    private final KgDocumentChunkMapper chunkMapper;
    private final KgScopeService scopeService;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, EntityFact> entities = new LinkedHashMap<>();
    private final Map<Long, RelationFact> relations = new LinkedHashMap<>();
    private final Map<Long, ChunkFact> chunks = new LinkedHashMap<>();
    private final Set<String> successfulTools = new LinkedHashSet<>();
    private final java.util.ArrayList<ToolCoverage> coverage = new java.util.ArrayList<>();
    private final java.util.ArrayList<NeighborPage> neighborPages = new java.util.ArrayList<>();

    public EvidenceLedger(KgEntityMapper entityMapper, KgRelationMapper relationMapper,
                          KgDocumentChunkMapper chunkMapper, KgScopeService scopeService) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.chunkMapper = chunkMapper;
        this.scopeService = scopeService;
    }

    public void addToolResult(String toolName, Object result, Long userId) {
        if (!ALLOWED_TOOLS.contains(toolName)) {
            return;
        }
        Map<String, Object> root = asMap(result);
        if (root == null || root.containsKey("error")) {
            return;
        }
        successfulTools.add(toolName);
        for (Map<String, Object> row : rows(root.get("entities"))) {
            addEntity(id(row.get("entityId")), row.get("name"), userId);
        }
        for (Map<String, Object> row : rows(root.get("neighbors"))) {
            addRelation(id(row.get("relationId")), row, userId);
        }
        for (Map<String, Object> row : rows(root.get("relations"))) {
            Long relationId = id(row.get("relationId"));
            addRelation(relationId, row, userId);
            if ("query_temporal".equals(toolName)) {
                addRelationSourceChunk(relationId, userId);
            }
        }
        for (Map<String, Object> row : rows(root.get("chunks"))) {
            addChunk(id(row.get("chunkId")), row, userId);
        }
        recordCoverage(toolName, root);
    }

    public boolean containsEntity(Long id) {
        return entities.containsKey(id);
    }

    public boolean containsRelation(Long id, Long sourceId, Long targetId) {
        RelationFact relation = relations.get(id);
        return relation != null && relation.sourceId.equals(sourceId)
            && relation.targetId.equals(targetId);
    }

    public boolean containsChunk(Long id) {
        return chunks.containsKey(id);
    }

    public Optional<EntityFact> entity(Long id) {
        return Optional.ofNullable(entities.get(id));
    }

    public Optional<RelationFact> relation(Long id) {
        return Optional.ofNullable(relations.get(id));
    }

    public Optional<ChunkFact> chunk(Long id) {
        return Optional.ofNullable(chunks.get(id));
    }

    public Collection<EntityFact> entities() {
        return Collections.unmodifiableCollection(entities.values());
    }

    public Collection<RelationFact> relations() {
        return Collections.unmodifiableCollection(relations.values());
    }

    public Collection<ChunkFact> chunks() {
        return Collections.unmodifiableCollection(chunks.values());
    }

    public boolean hasToolResult(String toolName) {
        return successfulTools.contains(toolName);
    }

    public List<ToolCoverage> coverage() { return List.copyOf(coverage); }

    public boolean completeRelations(String tool, Long entityId) {
        if ("expand_neighbors".equals(tool) && neighborPages.stream().anyMatch(page ->
            java.util.Objects.equals(page.entityId(), entityId))) return completeNeighborPages(entityId);
        return coverage.stream().anyMatch(item -> item.tool().equals(tool)
            && java.util.Objects.equals(item.entityId(), entityId) && item.complete()
            && relations.keySet().containsAll(item.relationIds()));
    }

    public Integer nextNeighborOffset(Long entityId) {
        for (int i = neighborPages.size() - 1; i >= 0; i--) {
            NeighborPage page = neighborPages.get(i);
            if (java.util.Objects.equals(page.entityId(), entityId))
                return page.countsComplete() ? page.nextOffset() : null;
        }
        return null;
    }

    private boolean completeNeighborPages(Long entityId) {
        List<NeighborPage> pages = neighborPages.stream()
            .filter(page -> java.util.Objects.equals(page.entityId(), entityId)).toList();
        if (pages.isEmpty()) return false;
        NeighborPage latest = pages.get(pages.size() - 1);
        if (!latest.countsComplete() || latest.fingerprint() == null) return false;
        int position = 0;
        Set<Long> ids = new LinkedHashSet<>();
        for (NeighborPage page : pages) {
            if (!latest.fingerprint().equals(page.fingerprint()) || page.visible() != latest.visible()) continue;
            if (!page.countsComplete() || page.offset() != position) continue;
            if (!relations.keySet().containsAll(page.relationIds())
                || page.relationIds().stream().anyMatch(ids::contains)) return false;
            ids.addAll(page.relationIds());
            position += page.relationIds().size();
            if (page.nextOffset() == null) {
                if (position != latest.visible() || ids.size() != latest.visible()) return false;
                List<GraphRelationFingerprint.Row> rows = ids.stream().map(id -> {
                    RelationFact edge = relations.get(id);
                    return new GraphRelationFingerprint.Row(edge.id(), edge.sourceId(), edge.targetId(), edge.type(),
                        edge.validFrom() == null ? null : edge.validFrom().getTime(),
                        edge.validTo() == null ? null : edge.validTo().getTime(),
                        entity(edge.sourceId()).map(EntityFact::name).orElse(""),
                        entity(edge.targetId()).map(EntityFact::name).orElse(""));
                }).toList();
                return latest.fingerprint().equals(GraphRelationFingerprint.of(rows));
            }
            if (page.nextOffset() != position) return false;
        }
        return false;
    }

    public boolean completeTemporal(Long entityId, KgTimeRange range) {
        if (range == null) return false;
        return coverage.stream().anyMatch(item -> item.tool().equals("query_temporal")
            && java.util.Objects.equals(item.entityId(), entityId) && item.complete()
            && range.startInclusive().toString().equals(item.startInclusive())
            && (range.endExclusive().toString().equals(item.endExclusive())
                || KgTimeRange.UNBOUNDED_END.equals(range.endExclusive()) && item.endExclusive() == null)
            && relations.keySet().containsAll(item.relationIds()));
    }

    private void recordCoverage(String tool, Map<String, Object> root) {
        Map<String, Object> metadata = asMap(root.get("coverage"));
        if (metadata == null) return;
        Map<String, Object> scope = asMap(metadata.get("scope"));
        if (scope == null) scope = Map.of();
        Set<Long> returnedIds = new LinkedHashSet<>();
        for (String key : List.of("relations", "neighbors")) {
            for (Map<String, Object> row : rows(root.get(key))) {
                Long relationId = id(row.get("relationId"));
                if (relationId != null) returnedIds.add(relationId);
            }
        }
        coverage.add(new ToolCoverage(tool, id(scope.get("entityId")),
            string(scope.get("startInclusive")), string(scope.get("endExclusive")),
            Boolean.TRUE.equals(metadata.get("complete")) && !Boolean.TRUE.equals(metadata.get("truncated"))
                && metadata.get("returned") instanceof Number returned && returned.intValue() == returnedIds.size(),
            Boolean.TRUE.equals(metadata.get("truncated")), Set.copyOf(returnedIds)));
        if ("expand_neighbors".equals(tool) && metadata.get("offset") instanceof Number offset
            && metadata.get("visible") instanceof Number visible && metadata.get("returned") instanceof Number returned
            && metadata.get("resultFingerprint") instanceof String fingerprint) {
            Integer next = metadata.get("nextOffset") instanceof Number value ? value.intValue() : null;
            boolean validPage = returned.intValue() == returnedIds.size() && offset.intValue() >= 0
                && offset.intValue() <= visible.intValue()
                && offset.intValue() + returnedIds.size() <= visible.intValue()
                && (next == null || next == offset.intValue() + returnedIds.size()
                    && next > offset.intValue() && next < visible.intValue());
            neighborPages.add(new NeighborPage(id(scope.get("entityId")), offset.intValue(), visible.intValue(),
                Set.copyOf(returnedIds), validPage && Boolean.TRUE.equals(metadata.get("countsComplete")), next, fingerprint));
        }
    }

    private static String string(Object value) { return value == null ? null : String.valueOf(value); }

    private void addEntity(Long id, Object reportedName, Long userId) {
        if (id == null || entities.containsKey(id)) {
            return;
        }
        KgEntity entity = entityMapper.selectById(id);
        if (entity == null || !KgEntity.STATUS_ACTIVE.equals(entity.getStatus())
            || !scopeService.canSee(entity, userId)
            || reportedName != null && !entity.getDisplayName().equals(String.valueOf(reportedName))) {
            return;
        }
        entities.put(id, new EntityFact(id, entity.getDisplayName(),
            entity.getEntityType(), entity.getDescription()));
    }

    private void addRelation(Long id, Map<String, Object> reported, Long userId) {
        if (id == null || relations.containsKey(id)) {
            return;
        }
        KgRelation relation = relationMapper.selectById(id);
        if (relation == null || !scopeService.canSee(relation.getScope(), relation.getTeamId(),
            relation.getUserId(), userId)
            || !relation.getSourceId().equals(id(reported.get("sourceId")))
            || !relation.getTargetId().equals(id(reported.get("targetId")))) {
            return;
        }
        KgEntity source = entityMapper.selectById(relation.getSourceId());
        KgEntity target = entityMapper.selectById(relation.getTargetId());
        if (source == null || target == null
            || !KgEntity.STATUS_ACTIVE.equals(source.getStatus())
            || !KgEntity.STATUS_ACTIVE.equals(target.getStatus())
            || !scopeService.canSee(source, userId) || !scopeService.canSee(target, userId)
            || reported.get("sourceName") != null
                && !source.getDisplayName().equals(String.valueOf(reported.get("sourceName")))
            || reported.get("targetName") != null
                && !target.getDisplayName().equals(String.valueOf(reported.get("targetName")))) {
            return;
        }
        addEntity(source.getId(), source.getDisplayName(), userId);
        addEntity(target.getId(), target.getDisplayName(), userId);
        relations.put(id, new RelationFact(id, relation.getSourceId(), relation.getTargetId(),
            relation.getRelationType(), relation.getValidFrom(), relation.getValidTo(),
            relation.getSourceDocId(), relation.getSourceChunkId(), relation.getFactText()));
    }

    private void addChunk(Long id, Map<String, Object> reported, Long userId) {
        if (id == null || chunks.containsKey(id)) {
            return;
        }
        KgDocumentChunk chunk = chunkMapper.selectById(id);
        if (chunk == null || !scopeService.canSee(chunk.getScope(), chunk.getTeamId(),
            chunk.getUserId(), userId)
            || !chunk.getDocType().equals(reported.get("docType"))
            || !chunk.getDocId().equals(id(reported.get("docId")))) {
            return;
        }
        chunks.put(id, new ChunkFact(id, chunk.getDocType(), chunk.getDocId(),
            chunk.getTitle(), chunk.getContent(), chunk.getStartOffset(), chunk.getEndOffset()));
    }

    private void addRelationSourceChunk(Long relationId, Long userId) {
        RelationFact relation = relations.get(relationId);
        if (relation == null || relation.sourceChunkId() == null
            || chunks.containsKey(relation.sourceChunkId())) {
            return;
        }
        KgDocumentChunk chunk = chunkMapper.selectById(relation.sourceChunkId());
        if (chunk == null || !scopeService.canSee(chunk.getScope(), chunk.getTeamId(),
            chunk.getUserId(), userId)
            || !(chunk.getDocType() + ":" + chunk.getDocId())
                .equals(relation.sourceDocId())) {
            return;
        }
        chunks.put(chunk.getId(), new ChunkFact(chunk.getId(), chunk.getDocType(),
            chunk.getDocId(), chunk.getTitle(), chunk.getContent(),
            chunk.getStartOffset(), chunk.getEndOffset()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object result) {
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (result instanceof String raw && raw.startsWith("{")) {
            try {
                return json.readValue(raw, Map.class);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance)
            .map(row -> (Map<String, Object>) row).toList();
    }

    private static Long id(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

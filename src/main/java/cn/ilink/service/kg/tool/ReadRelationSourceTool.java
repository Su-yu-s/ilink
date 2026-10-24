package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.kg.KgDocumentChunkService;
import cn.ilink.service.kg.KgScopeService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** B3 source acquisition from an authorized relation; literal retrieval never establishes a category. */
@Component
public class ReadRelationSourceTool extends AbstractGraphTool {
    private static final int MAX_RETURNED_CHUNKS = 2;
    private static final int MAX_SCANNED_CHUNKS = 128;
    private static final int MAX_DISPLAY_CHARS = 800;
    // These are the document types indexed by KgBackfillRunner; entity types are a different domain.
    private static final Pattern SOURCE_DOCUMENT = Pattern.compile("(POST|COMPETITION|TASK|ASSET):([1-9][0-9]*)");

    private final KgRelationMapper relationMapper;
    private final KgEntityMapper entityMapper;
    private final KgDocumentChunkService chunks;

    public ReadRelationSourceTool(KgScopeService scopeService, KgRelationMapper relationMapper,
                                  KgEntityMapper entityMapper, KgDocumentChunkService chunks) {
        super(scopeService);
        this.relationMapper = relationMapper;
        this.entityMapper = entityMapper;
        this.chunks = chunks;
    }

    @Override
    public String getName() { return "read_relation_source"; }

    @Override
    public String getDescription() {
        return "沿已确认关系补查该关系来源文档中的成员名称，返回最多两个可见原文片段及命中坐标。"
            + "仅核对限定来源，不判断分类，不证明全局存在或不存在。";
    }

    @Override
    public String getParametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
            + "\"relation_id\":{\"type\":\"integer\"},"
            + "\"member_entity_id\":{\"type\":\"integer\"}"
            + "},\"required\":[\"relation_id\",\"member_entity_id\"]}";
    }

    @Override
    public Object execute(Map<String, Object> params, Long userId) {
        Long relationId = paramLong(params, "relation_id");
        Long memberId = paramLong(params, "member_entity_id");
        if (relationId == null || relationId <= 0 || memberId == null || memberId <= 0)
            return errorJson("INVALID_PARAMS", "relation_id 和 member_entity_id 必须是正整数");
        KgRelation relation = relationMapper.selectById(relationId);
        if (relation == null) return errorJson("NOT_FOUND", "关系不存在或不可访问");
        if (!Objects.equals(relationId, relation.getId()))
            return errorJson("INVALID_RELATION_BINDING", "关系身份无法核对");
        if (!scopeService.canSee(relation.getScope(), relation.getTeamId(), relation.getUserId(), userId))
            return errorJson("PERMISSION_DENIED", "关系不存在或不可访问");
        if (!Objects.equals(memberId, relation.getSourceId()) && !Objects.equals(memberId, relation.getTargetId()))
            return errorJson("MEMBER_ENDPOINT_MISMATCH", "成员不是该关系的端点");
        if (relation.getSourceId() == null || relation.getTargetId() == null)
            return errorJson("INVALID_RELATION_BINDING", "关系端点无法核对");
        KgEntity source = entityMapper.selectById(relation.getSourceId());
        KgEntity target = entityMapper.selectById(relation.getTargetId());
        if ((source != null && !Objects.equals(source.getId(), relation.getSourceId()))
            || (target != null && !Objects.equals(target.getId(), relation.getTargetId())))
            return errorJson("INVALID_RELATION_BINDING", "关系端点身份无法核对");
        if (!visibleActive(source, userId) || !visibleActive(target, userId))
            return errorJson("PERMISSION_DENIED", "关系端点不存在或不可访问");
        KgEntity member = Objects.equals(memberId, source.getId()) ? source : target;
        String name = member.getDisplayName();
        if (name == null || name.isBlank())
            return errorJson("INVALID_MEMBER_NAME", "成员没有可核对的名称");
        String needle = name.trim();
        var document = sourceDocument(relation.getSourceDocId());
        if (document == null)
            return errorJson("NO_BOUND_SOURCE_DOCUMENT", "该关系没有可核对的来源文档标识");

        List<KgDocumentChunk> raw = chunks.listByDocument(document.type(), document.id(), userId);
        Map<Long, KgDocumentChunk> visible = new LinkedHashMap<>();
        if (raw != null) for (KgDocumentChunk chunk : raw) {
            if (chunk == null || chunk.getId() == null || chunk.getId() <= 0
                || !document.type().equals(chunk.getDocType()) || !document.id().equals(chunk.getDocId())
                || !scopeService.canSeeChunk(chunk, userId)) continue;
            visible.putIfAbsent(chunk.getId(), chunk);
        }
        List<KgDocumentChunk> ordered = new ArrayList<>(visible.values());
        ordered.sort(Comparator.<KgDocumentChunk, Boolean>comparing(
            chunk -> !Objects.equals(relation.getSourceChunkId(), chunk.getId()))
            .thenComparing(KgDocumentChunk::getOrdinalNo, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(KgDocumentChunk::getId));

        int scanned = 0, matched = 0, hitCount = 0;
        List<Map<String, Object>> returned = new ArrayList<>();
        List<Map<String, Object>> authorizedHits = new ArrayList<>();
        for (KgDocumentChunk chunk : ordered) {
            if (scanned >= MAX_SCANNED_CHUNKS) break;
            scanned++;
            String content = Objects.toString(chunk.getContent(), "");
            List<Map<String, Object>> positions = literalPositions(chunk, content, needle);
            if (positions.isEmpty()) continue;
            matched++; hitCount += positions.size();
            if (returned.size() >= MAX_RETURNED_CHUNKS) continue;
            String displayed = displayText(content);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", chunk.getId()); row.put("docType", chunk.getDocType());
            row.put("docId", chunk.getDocId()); row.put("title", chunk.getTitle());
            row.put("startOffset", chunk.getStartOffset()); row.put("endOffset", chunk.getEndOffset());
            row.put("text", displayed); row.put("displayTextComplete", displayed.length() == content.length());
            row.put("memberNameMatches", positions);
            returned.add(row);
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("chunkId", chunk.getId()); hit.put("docType", document.type()); hit.put("docId", document.id());
            hit.put("declaredSourceChunk", Objects.equals(relation.getSourceChunkId(), chunk.getId()));
            hit.put("coordinatesVerified", coordinatesValid(chunk, content)); hit.put("matches", positions);
            authorizedHits.add(hit);
        }
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("relationId", relation.getId()); binding.put("sourceId", source.getId());
        binding.put("targetId", target.getId()); binding.put("relationType", relation.getRelationType());
        binding.put("sourceDocId", relation.getSourceDocId()); binding.put("sourceChunkId", relation.getSourceChunkId());
        binding.put("factText", relation.getFactText()); binding.put("memberEntityId", memberId);
        binding.put("memberName", name); binding.put("endpointsAuthorized", true);
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("scope", "VISIBLE_CHUNKS_OF_RELATION_SOURCE_DOCUMENT");
        search.put("docType", document.type()); search.put("docId", document.id());
        search.put("literalMatchPolicy", "CASE_INSENSITIVE_BOUNDED_REGION_MATCHES_SOURCE_OFFSETS");
        search.put("visibleChunkCount", visible.size()); search.put("scannedChunkCount", scanned);
        search.put("scanLimit", MAX_SCANNED_CHUNKS); search.put("scanComplete", scanned == visible.size());
        search.put("matchedChunkCount", matched); search.put("literalHitCount", hitCount);
        search.put("returnedChunkCount", returned.size()); search.put("returnLimit", MAX_RETURNED_CHUNKS);
        search.put("allMatchedChunksReturned", returned.size() == matched);
        search.put("resultReason", returned.isEmpty() ? "NO_MATCH_IN_SCANNED_VISIBLE_SOURCE" : "AUTHORIZED_LITERAL_SOURCE_FOUND");
        search.put("globalAbsenceEstablished", false); search.put("semanticSupportEstablished", false);
        search.put("authorizedHits", authorizedHits);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", returned.size()); result.put("sourceBinding", binding);
        result.put("sourceSearch", search); result.put("chunks", returned);
        return result;
    }

    private boolean visibleActive(KgEntity entity, Long userId) {
        return entity != null && KgEntity.STATUS_ACTIVE.equals(entity.getStatus()) && scopeService.canSee(entity, userId);
    }

    private record SourceDocument(String type, Long id) { }

    private static SourceDocument sourceDocument(String source) {
        if (source == null) return null;
        var match = SOURCE_DOCUMENT.matcher(source);
        if (!match.matches()) return null;
        try { return new SourceDocument(match.group(1), Long.valueOf(match.group(2))); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static boolean coordinatesValid(KgDocumentChunk chunk, String content) {
        return chunk.getStartOffset() != null && chunk.getEndOffset() != null && chunk.getStartOffset() >= 0
            && (long) chunk.getEndOffset() - chunk.getStartOffset() == content.length();
    }

    private static List<Map<String, Object>> literalPositions(KgDocumentChunk chunk, String content, String needle) {
        List<Map<String, Object>> hits = new ArrayList<>();
        boolean coordinates = coordinatesValid(chunk, content);
        for (int offset = 0; offset + needle.length() <= content.length(); offset++) {
            if (!content.regionMatches(true, offset, needle, 0, needle.length())
                || !boundedOccurrence(content, needle, offset)) continue;
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("localStart", offset); position.put("localEnd", offset + needle.length());
            if (coordinates) {
                position.put("sourceStart", chunk.getStartOffset() + offset);
                position.put("sourceEnd", chunk.getStartOffset() + offset + needle.length());
            }
            hits.add(position);
        }
        return List.copyOf(hits);
    }

    // Keep retrieval's ASCII token boundary identical to ClassificationCoverageProofs.
    // Neither this boundary nor a name match establishes an ecosystem classification.
    private static boolean boundedOccurrence(String text, String needle, int offset) {
        boolean ascii = needle.codePoints().allMatch(point -> point < 128);
        return !ascii || (offset == 0 || !continuesBefore(text, offset - 1))
            && (offset + needle.length() == text.length() || !continuesAfter(text, offset + needle.length()));
    }

    private static boolean continuesBefore(String text, int index) {
        char value = text.charAt(index);
        if (value == '.' || value == '-') return index > 0 && tokenPart(text.charAt(index - 1));
        return tokenPart(value);
    }

    private static boolean continuesAfter(String text, int index) {
        char value = text.charAt(index);
        if (value == '.' || value == '-') return index + 1 < text.length() && tokenPart(text.charAt(index + 1));
        return tokenPart(value);
    }

    private static boolean tokenPart(char value) {
        return value < 128 && (Character.isLetterOrDigit(value) || "_+#.-".indexOf(value) >= 0);
    }

    private static String displayText(String content) {
        int end = Math.min(content.length(), MAX_DISPLAY_CHARS);
        if (end > 0 && end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) end--;
        return content.substring(0, end);
    }
}

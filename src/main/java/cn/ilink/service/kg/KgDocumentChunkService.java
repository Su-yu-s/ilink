package cn.ilink.service.kg;

import cn.ilink.config.AiEmbeddingProperties;
import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgIndexTask;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgIndexTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/** Splits source documents into stable, overlapping text units. */
@Service
@Slf4j
public class KgDocumentChunkService {
    static final int CHUNK_CODE_POINTS = 800;
    static final int OVERLAP_CODE_POINTS = 120;

    private final KgDocumentChunkMapper mapper;
    private final KgEmbeddingService embeddings;
    private final KgScopeService scopeService;
    private final AiEmbeddingProperties properties;
    private final KgIndexTaskMapper indexTaskMapper;

    @Autowired
    public KgDocumentChunkService(KgDocumentChunkMapper mapper, KgEmbeddingService embeddings,
                                  KgScopeService scopeService, AiEmbeddingProperties properties,
                                  KgIndexTaskMapper indexTaskMapper) {
        this.mapper = mapper;
        this.embeddings = embeddings;
        this.scopeService = scopeService;
        this.properties = properties;
        this.indexTaskMapper = indexTaskMapper;
    }

    /** Compatibility constructor for isolated retrieval/chunk tests. */
    KgDocumentChunkService(KgDocumentChunkMapper mapper, KgEmbeddingService embeddings,
                           KgScopeService scopeService, AiEmbeddingProperties properties) {
        this(mapper, embeddings, scopeService, properties, null);
    }

    public static List<KgDocumentChunk> split(KgIndexTask task) {
        List<KgDocumentChunk> chunks = new ArrayList<>();
        if (task == null || task.getContent() == null || task.getContent().isBlank()) {
            return chunks;
        }
        String content = KgSourceText.clean(task.getContent());
        if (content.isBlank()) {
            return chunks;
        }
        int offset = 0;
        for (int ordinal = 0; offset < content.length(); ordinal++) {
            int remaining = content.codePointCount(offset, content.length());
            int end = content.offsetByCodePoints(offset, Math.min(CHUNK_CODE_POINTS, remaining));
            KgDocumentChunk chunk = new KgDocumentChunk();
            chunk.setDocType(task.getDocType());
            chunk.setDocId(task.getDocId());
            chunk.setOrdinalNo(ordinal);
            chunk.setTitle(task.getTitle());
            chunk.setContent(content.substring(offset, end));
            chunk.setStartOffset(offset);
            chunk.setEndOffset(end);
            chunk.setScope(task.getScope() == null ? KgEntity.SCOPE_GLOBAL : task.getScope());
            chunk.setTeamId(task.getTeamId() == null ? 0L : task.getTeamId());
            chunk.setUserId(task.getUserId() == null ? 0L : task.getUserId());
            chunk.setContentHash(sha256(chunk.getContent()));
            chunks.add(chunk);
            if (end == content.length()) {
                break;
            }
            offset = content.offsetByCodePoints(end, -OVERLAP_CODE_POINTS);
        }
        return chunks;
    }

    /** Replace all chunks from one document; textual evidence survives embedding outages. */
    @Transactional
    public void index(KgIndexTask task) {
        assertWriteFence(task);
        List<KgDocumentChunk> desired = split(task);
        List<KgDocumentChunk> current = listIndexedByDocument(task);
        if (sameSource(current, desired)) {
            return;
        }
        mapper.delete(new QueryWrapper<KgDocumentChunk>()
            .eq("doc_type", task.getDocType()).eq("doc_id", task.getDocId()));
        for (KgDocumentChunk chunk : desired) {
            try {
                float[] vector = embeddings.embedText(
                    (chunk.getTitle() == null ? "" : chunk.getTitle() + "\n") + chunk.getContent());
                chunk.setEmbedding(KgEmbeddingService.serializeVector(vector));
                chunk.setEmbeddingDim(vector.length);
                chunk.setEmbeddingModel(properties.getModel());
            } catch (IllegalStateException e) {
                log.debug("[KG] 文档片段向量不可用: {}:{}#{}",
                    chunk.getDocType(), chunk.getDocId(), chunk.getOrdinalNo());
            }
            mapper.insert(chunk);
        }
    }

    /** Remove every indexed slice belonging to a deleted source document. */
    @Transactional
    public int deleteByDocument(KgIndexTask task) {
        if (task == null) return 0;
        assertWriteFence(task);
        return mapper.delete(new QueryWrapper<KgDocumentChunk>()
            .eq("doc_type", task.getDocType())
            .eq("doc_id", task.getDocId()));
    }

    /**
     * Lock the queue row for the duration of the chunk publication. A newer
     * enqueue either commits first (making this fence fail) or waits until the
     * current publication commits and then schedules a fresh revision.
     */
    private void assertWriteFence(KgIndexTask task) {
        if (task == null || task.getLeaseToken() == null || task.getLeaseToken().isBlank()
            || indexTaskMapper == null) {
            return;
        }
        KgIndexTask current = indexTaskMapper.selectByIdForUpdate(task.getId());
        if (current == null
            || !KgIndexTask.RUNNING.equals(current.getStatus())
            || !java.util.Objects.equals(task.getLeaseToken(), current.getLeaseToken())
            || !java.util.Objects.equals(task.getLeaseOwner(), current.getLeaseOwner())
            || !java.util.Objects.equals(
                task.getRequestedRevision() == null ? 0L : task.getRequestedRevision(),
                current.getRequestedRevision() == null ? 0L : current.getRequestedRevision())) {
            throw new IllegalStateException("KG_INDEX_LEASE_LOST");
        }
    }

    /** Internal source lookup for indexing; scoped by the task itself. */
    public List<KgDocumentChunk> listIndexedByDocument(KgIndexTask task) {
        List<KgDocumentChunk> rows = mapper.selectList(new QueryWrapper<KgDocumentChunk>()
            .eq("doc_type", task.getDocType()).eq("doc_id", task.getDocId())
            .eq("scope", task.getScope() == null ? KgEntity.SCOPE_GLOBAL : task.getScope())
            .eq("team_id", task.getTeamId() == null ? 0L : task.getTeamId())
            .eq("user_id", task.getUserId() == null ? 0L : task.getUserId())
            .orderByAsc("ordinal_no"));
        return rows == null ? List.of() : rows;
    }

    private static boolean sameSource(List<KgDocumentChunk> current,
                                      List<KgDocumentChunk> desired) {
        if (current.isEmpty() || current.size() != desired.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            KgDocumentChunk old = current.get(i);
            KgDocumentChunk next = desired.get(i);
            if (old.getId() == null || !next.getContentHash().equals(old.getContentHash())
                || !java.util.Objects.equals(old.getTitle(), next.getTitle())
                || !java.util.Objects.equals(old.getStartOffset(), next.getStartOffset())
                || !java.util.Objects.equals(old.getEndOffset(), next.getEndOffset())
                || !java.util.Objects.equals(old.getScope(), next.getScope())
                || !java.util.Objects.equals(old.getTeamId(), next.getTeamId())
                || !java.util.Objects.equals(old.getUserId(), next.getUserId())) {
                return false;
            }
        }
        return true;
    }

    /** Visible top-k ordinary RAG retrieval using vector and lexical rank fusion. */
    public List<KgDocumentChunk> retrieve(String question, Long userId, int k) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(20, k));
        List<KgDocumentChunk> visible = new ArrayList<>();
        for (KgDocumentChunk row : mapper.selectList(visibleQuery(userId))) {
            if (scopeService.canSeeChunk(row, userId)) {
                visible.add(row);
            }
        }
        float[] queryVector = null;
        try {
            queryVector = embeddings.embedText(question);
        } catch (IllegalStateException e) {
            log.debug("[KG] 片段检索改用文本匹配: {}", e.getMessage());
        }
        List<ScoredChunk> vectorHits = new ArrayList<>();
        if (queryVector != null) {
            for (KgDocumentChunk row : visible) {
                if (row.getEmbedding() == null
                    || !properties.getModel().equals(row.getEmbeddingModel())) {
                    continue;
                }
                try {
                    double score = KgEmbeddingService.cosineSimilarity(queryVector,
                        KgEmbeddingService.deserializeVector(row.getEmbedding()));
                    vectorHits.add(new ScoredChunk(row, score));
                } catch (IllegalArgumentException e) {
                    log.debug("[KG] 跳过维度不符的片段向量: {}", row.getId());
                }
            }
        }
        List<ScoredChunk> lexicalHits = new ArrayList<>();
        for (KgDocumentChunk row : visible) {
            double score = lexicalScore(question, row);
            if (score > 0) {
                lexicalHits.add(new ScoredChunk(row, score));
            }
        }
        Comparator<ScoredChunk> ranking = Comparator.comparingDouble(ScoredChunk::score).reversed()
            .thenComparing(s -> s.chunk().getId(), Comparator.nullsLast(Long::compareTo));
        vectorHits.sort(ranking);
        lexicalHits.sort(ranking);
        Map<Long, Double> fused = new HashMap<>();
        Map<Long, KgDocumentChunk> byId = new HashMap<>();
        addRanks(vectorHits, fused, byId);
        addRanks(lexicalHits, fused, byId);
        List<ScoredChunk> scored = fused.entrySet().stream()
            .map(entry -> new ScoredChunk(byId.get(entry.getKey()), entry.getValue()))
            .sorted(ranking).toList();
        List<KgDocumentChunk> result = new ArrayList<>();
        for (ScoredChunk hit : scored) {
            if (result.size() >= limit) {
                break;
            }
            result.add(hit.chunk());
        }
        return result;
    }

    private static void addRanks(List<ScoredChunk> ranked, Map<Long, Double> fused,
                                 Map<Long, KgDocumentChunk> byId) {
        for (int rank = 0; rank < ranked.size(); rank++) {
            KgDocumentChunk chunk = ranked.get(rank).chunk();
            if (chunk.getId() != null) {
                byId.put(chunk.getId(), chunk);
                fused.merge(chunk.getId(), 1.0 / (60 + rank + 1), Double::sum);
            }
        }
    }

    public List<KgDocumentChunk> listByDocument(String docType, Long docId, Long userId) {
        List<KgDocumentChunk> rows = mapper.selectList(visibleQuery(userId)
            .eq("doc_type", docType).eq("doc_id", docId).orderByAsc("ordinal_no"));
        return rows.stream().filter(row ->
            scopeService.canSeeChunk(row, userId)).toList();
    }

    private QueryWrapper<KgDocumentChunk> visibleQuery(Long userId) {
        QueryWrapper<KgDocumentChunk> query = new QueryWrapper<>();
        query.and(w -> {
            w.eq("scope", KgEntity.SCOPE_GLOBAL);
            if (userId != null && userId > 0) {
                w.or().eq("scope", KgEntity.SCOPE_PRIVATE).eq("user_id", userId);
                List<Long> teams = scopeService.visibleTeamIds(userId);
                if (teams != null && !teams.isEmpty()) {
                    w.or().eq("scope", KgEntity.SCOPE_TEAM).in("team_id", teams);
                }
            }
        });
        return query;
    }

    private static double lexicalScore(String question, KgDocumentChunk row) {
        String q = question.toLowerCase(Locale.ROOT).trim();
        String text = ((row.getTitle() == null ? "" : row.getTitle()) + " " + row.getContent())
            .toLowerCase(Locale.ROOT);
        if (text.contains(q)) {
            return q.length() + 1;
        }
        double score = 0;
        for (String token : q.split("[\\s，。？！、；：,.?!;:（）()\\[\\]【】\\\"']+")) {
            if (token.length() >= 2 && text.contains(token)) {
                score += token.length();
            }
        }
        String cjk = q.replaceAll("[^\\p{IsHan}]", "");
        for (int width = 2; width <= 3; width++) {
            java.util.Set<String> terms = new java.util.HashSet<>();
            for (int index = 0; index + width <= cjk.length(); index++) {
                terms.add(cjk.substring(index, index + width));
            }
            for (String term : terms) {
                if (text.contains(term)) {
                    score += width == 3 ? 1.5 : 0.5;
                }
            }
        }
        return score;
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ScoredChunk(KgDocumentChunk chunk, double score) {
    }
}

package cn.ilink.service.kg;

import cn.ilink.config.AiEmbeddingProperties;
import cn.ilink.entity.KgEntityEmbedding;
import cn.ilink.mapper.KgEntityEmbeddingMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * KG 向量服务：调用 Qwen Embedding（OpenAI 兼容 /embeddings 接口），
 * float32 小端序列化存 BLOB；检索在内存中做余弦相似度（毕设数据量 &lt; 1 万条，毫秒级）。
 * 未配置 EMBEDDING_API_KEY 时全部能力静默降级（返回空结果，不抛异常）。
 */
@Service
@Slf4j
public class KgEmbeddingService {

    private final AiEmbeddingProperties properties;
    private final KgEntityEmbeddingMapper embeddingMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 内存向量索引：脏标记触发重建，避免每次检索都全表读 BLOB */
    private volatile EmbeddingIndex index;
    private volatile boolean dirty = true;

    @Autowired
    public KgEmbeddingService(AiEmbeddingProperties properties, KgEntityEmbeddingMapper embeddingMapper) {
        this.properties = properties;
        this.embeddingMapper = embeddingMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(Math.max(10_000, properties.getTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
    }

    /** 内存中的全量向量索引（当前模型维度一致） */
    private static class EmbeddingIndex {
        final String model;
        final long[] entityIds;
        final float[][] vectors;

        EmbeddingIndex(String model, long[] entityIds, float[][] vectors) {
            this.model = model;
            this.entityIds = entityIds;
            this.vectors = vectors;
        }
    }

    // ==================== 序列化（小端 float32） ====================

    public static byte[] serializeVector(float[] vec) {
        ByteBuffer bb = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vec) {
            bb.putFloat(f);
        }
        return bb.array();
    }

    public static float[] deserializeVector(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / 4];
        for (int i = 0; i < result.length; i++) {
            result[i] = bb.getFloat();
        }
        return result;
    }

    /** 余弦相似度。零向量返回 0；维度不同抛 IllegalArgumentException。 */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("向量维度不一致或为空");
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ==================== Embedding 调用 ====================

    /**
     * 调 Qwen Embedding 接口向量化一段文本。
     *
     * @throws IllegalStateException 未配置或调用失败（调用方决定降级策略）
     */
    public float[] embedText(String text) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("Embedding 服务未配置");
        }
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalStateException("向量化文本为空");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel().trim());
        body.put("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey().trim());

        try {
            String resp = restTemplate.postForEntity(
                properties.getBaseUrl().trim() + "/embeddings",
                new HttpEntity<>(body.toString(), headers),
                String.class).getBody();
            JsonNode root = objectMapper.readTree(resp);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Embedding 返回缺少 data");
            }
            JsonNode embedding = data.get(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new IllegalStateException("Embedding 返回缺少 embedding 数组");
            }
            float[] vec = new float[embedding.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) embedding.get(i).asDouble();
            }
            return vec;
        } catch (ResourceAccessException e) {
            log.warn("[KG] Embedding 连接失败: {}", e.getMessage());
            throw new IllegalStateException("Embedding 服务连接超时");
        } catch (RestClientResponseException e) {
            log.warn("[KG] Embedding 返回异常状态 {}: {}", e.getRawStatusCode(), e.getStatusText());
            throw new IllegalStateException("Embedding 服务暂时不可用");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[KG] Embedding 解析失败: {}", e.getMessage());
            throw new IllegalStateException("Embedding 返回内容无法解析");
        }
    }

    /**
     * 向量化并落库（按 entity_id + model upsert）。任何失败只记日志不抛出——
     * 向量缺失只影响向量检索，不影响图谱本身。
     *
     * @return 是否成功写入
     */
    public boolean embedAndSave(Long entityId, String text) {
        try {
            float[] vec = embedText(text);
            String hash = sha256(text);
            KgEntityEmbedding existing = embeddingMapper.selectOne(
                new QueryWrapper<KgEntityEmbedding>()
                    .eq("entity_id", entityId)
                    .eq("model", properties.getModel().trim()));
            if (existing == null) {
                KgEntityEmbedding row = new KgEntityEmbedding();
                row.setEntityId(entityId);
                row.setModel(properties.getModel().trim());
                row.setDim(vec.length);
                row.setVector(serializeVector(vec));
                row.setTextHash(hash);
                row.setCreatedAt(new Date());
                row.setUpdatedAt(new Date());
                embeddingMapper.insert(row);
            } else {
                existing.setDim(vec.length);
                existing.setVector(serializeVector(vec));
                existing.setTextHash(hash);
                existing.setUpdatedAt(new Date());
                embeddingMapper.updateById(existing);
            }
            dirty = true;
            return true;
        } catch (Exception e) {
            log.warn("[KG] 实体 {} 向量化失败（降级跳过）: {}", entityId, e.getMessage());
            return false;
        }
    }

    // ==================== 检索 ====================

    /** 召回结果：实体ID + 相似度 */
    public static class ScoredEntity {
        public final Long entityId;
        public final double score;

        public ScoredEntity(Long entityId, double score) {
            this.entityId = entityId;
            this.score = score;
        }
    }

    /**
     * 全量内存余弦检索 top-k。配置缺失或索引为空时返回空列表（降级）。
     */
    public List<ScoredEntity> searchTopK(float[] queryVec, int k) {
        List<ScoredEntity> results = new ArrayList<>();
        if (queryVec == null || queryVec.length == 0 || k <= 0) {
            return results;
        }
        EmbeddingIndex idx = currentIndex();
        if (idx == null) {
            return results;
        }
        // 简单选择法取 top-k：k 通常 ≤ 8，无需全排序
        double[] bestScores = new double[k];
        Arrays.fill(bestScores, Double.NEGATIVE_INFINITY);
        long[] bestIds = new long[k];
        for (int i = 0; i < idx.entityIds.length; i++) {
            double score;
            try {
                score = cosineSimilarity(queryVec, idx.vectors[i]);
            } catch (IllegalArgumentException e) {
                continue; // 维度不一致的脏数据直接跳过
            }
            for (int j = 0; j < k; j++) {
                if (score > bestScores[j]) {
                    // 后移插入
                    for (int m = k - 1; m > j; m--) {
                        bestScores[m] = bestScores[m - 1];
                        bestIds[m] = bestIds[m - 1];
                    }
                    bestScores[j] = score;
                    bestIds[j] = idx.entityIds[i];
                    break;
                }
            }
        }
        for (int j = 0; j < k; j++) {
            if (bestIds[j] > 0) {
                results.add(new ScoredEntity(bestIds[j], bestScores[j]));
            }
        }
        return results;
    }

    /** 获取（必要时重建）当前模型的内存向量索引 */
    private EmbeddingIndex currentIndex() {
        if (dirty) {
            synchronized (this) {
                if (dirty) {
                    index = rebuildIndex();
                    dirty = false;
                }
            }
        }
        return index;
    }

    private EmbeddingIndex rebuildIndex() {
        String model = properties.getModel().trim();
        try {
            List<KgEntityEmbedding> rows = embeddingMapper.selectList(
                new QueryWrapper<KgEntityEmbedding>().eq("model", model));
            if (rows.isEmpty()) {
                return null;
            }
            long[] ids = new long[rows.size()];
            float[][] vecs = new float[rows.size()][];
            int kept = 0;
            for (KgEntityEmbedding row : rows) {
                try {
                    vecs[kept] = deserializeVector(row.getVector());
                    ids[kept] = row.getEntityId();
                    kept++;
                } catch (Exception e) {
                    log.warn("[KG] 跳过损坏的向量记录 entity={}: {}", row.getEntityId(), e.getMessage());
                }
            }
            long[] finalIds = new long[kept];
            float[][] finalVecs = new float[kept][];
            System.arraycopy(ids, 0, finalIds, 0, kept);
            System.arraycopy(vecs, 0, finalVecs, 0, kept);
            return new EmbeddingIndex(model, finalIds, finalVecs);
        } catch (Exception e) {
            log.warn("[KG] 向量索引重建失败: {}", e.getMessage());
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

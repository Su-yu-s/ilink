package cn.ilink.service.kg;

import cn.ilink.config.AiEmbeddingProperties;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgEntityEmbedding;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityEmbeddingMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.ai.AiClient;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【性能基准 · 测试代码】检索链路的耗时基准（不含真实网络调用）。
 *
 * <p>度量三个环节：
 * ① 向量索引重建（BLOB 反序列化）+ 内存余弦 top-k 检索（1000 条 × 1024 维）；
 * ② 子图文本构建（40 实体 / 60 关系）；
 * ③ 快速检索编排开销（GraphRagService.query 全流程，模型调用被 mock 为瞬时返回）。
 *
 * <p>阈值设置为宽松上限（避免不同机器抖动导致误报），同时打印 P50/P95 供报告引用。
 * 说明：这里的耗时是**本机单线程**数据，用于横向比较各环节量级，不代表线上 SLA。
 */
class KgRetrievalPerformanceTest {

    private static final int DIM = 1024;
    private static final int ENTITY_COUNT = 1000;
    private static final int WARMUP = 5;
    private static final int ITERATIONS = 30;

    private KgEmbeddingService embeddingService;
    private GraphRagService graphRagService;

    @BeforeEach
    void setUp() {
        AiEmbeddingProperties properties = new AiEmbeddingProperties();
        properties.setModel("perf-test-model");

        KgEntityEmbeddingMapper embeddingMapper = mock(KgEntityEmbeddingMapper.class);
        when(embeddingMapper.selectList(any(Wrapper.class))).thenReturn(buildEmbeddingRows());

        embeddingService = new KgEmbeddingService(properties, embeddingMapper);

        KgRelationService relationService = mock(KgRelationService.class);
        KgEntityMapper entityMapper = mock(KgEntityMapper.class);
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("基准答案", 10, 20));

        when(relationService.expandTwoHops(any())).thenReturn(buildSubGraph());
        when(relationService.getEntitiesByIds(any())).thenReturn(buildEntities(40));

        graphRagService = new GraphRagService(embeddingService, relationService, entityMapper, aiClient);
    }

    @Test
    @DisplayName("基准 1：1000 条 × 1024 维向量的索引重建 + 余弦 top-8 检索")
    void benchmark_vectorIndexAndSearch() {
        float[] query = randomVector();

        // 首次调用触发索引重建（含 1000 条 BLOB 反序列化）
        long buildStart = System.nanoTime();
        List<KgEmbeddingService.ScoredEntity> first = embeddingService.searchTopK(query, 8);
        double buildMs = (System.nanoTime() - buildStart) / 1_000_000.0;

        assertEquals(8, first.size(), "应返回 top-8");
        assertTrue(first.get(0).score >= first.get(7).score, "结果应按相似度降序");

        long[] samples = new long[ITERATIONS];
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            long start = System.nanoTime();
            embeddingService.searchTopK(query, 8);
            long cost = System.nanoTime() - start;
            if (i >= WARMUP) {
                samples[i - WARMUP] = cost;
            }
        }
        double[] stats = stats(samples);
        System.out.printf("[基准1] 索引重建(含反序列化 %d 条×%d 维)=%.1f ms；检索 P50=%.2f ms P95=%.2f ms max=%.2f ms%n",
            ENTITY_COUNT, DIM, buildMs, stats[0], stats[1], stats[2]);

        assertTrue(buildMs < 3000, "索引重建应低于 3s，实际 " + buildMs + " ms");
        assertTrue(stats[1] < 100, "top-8 检索 P95 应低于 100ms，实际 " + stats[1] + " ms");
    }

    @Test
    @DisplayName("基准 2：子图文本构建（40 实体 / 60 关系）")
    void benchmark_subgraphTextBuild() {
        List<KgEntity> entities = buildEntities(40);
        List<KgRelation> edges = buildSubGraph().edges;

        long[] samples = new long[ITERATIONS];
        String text = null;
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            long start = System.nanoTime();
            text = graphRagService.buildSubgraphText(entities, edges);
            long cost = System.nanoTime() - start;
            if (i >= WARMUP) {
                samples[i - WARMUP] = cost;
            }
        }
        double[] stats = stats(samples);
        System.out.printf("[基准2] 子图文本构建 P50=%.2f ms P95=%.2f ms；输出 %d 字符%n",
            stats[0], stats[1], text == null ? 0 : text.length());

        assertTrue(text != null && text.contains("[COMPETITION]"), "子图文本应包含实体行");
        assertTrue(stats[1] < 50, "P95 应低于 50ms，实际 " + stats[1] + " ms");
    }

    @Test
    @DisplayName("基准 3：快速检索全流程编排开销（模型调用被 mock）")
    void benchmark_fastRagOrchestration() {
        long[] samples = new long[ITERATIONS];
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            long start = System.nanoTime();
            GraphRagService.GraphRagAnswer answer = graphRagService.query("数学建模需要哪些技能", 7L);
            long cost = System.nanoTime() - start;
            if (i == WARMUP) {
                assertEquals("基准答案", answer.answer);
            }
            if (i >= WARMUP) {
                samples[i - WARMUP] = cost;
            }
        }
        double[] stats = stats(samples);
        System.out.printf("[基准3] 快速检索编排（向量检索+2跳扩展+子图构建+引用组装，不含网络）P50=%.2f ms P95=%.2f ms%n",
            stats[0], stats[1]);

        assertTrue(stats[1] < 200, "编排 P95 应低于 200ms，实际 " + stats[1] + " ms");
    }

    @Test
    @DisplayName("基准 4：余弦相似度单次计算（1024 维）与批量吞吐")
    void benchmark_cosineThroughput() {
        float[] a = randomVector();
        float[] b = randomVector();

        long start = System.nanoTime();
        int rounds = 10_000;
        double sink = 0;
        for (int i = 0; i < rounds; i++) {
            sink += KgEmbeddingService.cosineSimilarity(a, b);
        }
        double perCallUs = (System.nanoTime() - start) / 1000.0 / rounds;
        System.out.printf("[基准4] 单次余弦(1024维)=%.2f μs（1 万次累计 %.1f ms；sink=%.3f）%n",
            perCallUs, perCallUs * rounds / 1000.0, sink);

        assertTrue(perCallUs < 20, "单次余弦应低于 20μs，实际 " + perCallUs + " μs");
        assertEquals(1.0, sink / rounds, 1e-6, "同批向量重复计算应稳定（可忽略的浮点误差）");
    }

    // ==================== 数据构造 ====================

    private static List<KgEntityEmbedding> buildEmbeddingRows() {
        Random random = new Random(42);
        List<KgEntityEmbedding> rows = new ArrayList<>(ENTITY_COUNT);
        for (int i = 0; i < ENTITY_COUNT; i++) {
            float[] vector = new float[DIM];
            for (int d = 0; d < DIM; d++) {
                vector[d] = random.nextFloat() - 0.5f;
            }
            KgEntityEmbedding row = new KgEntityEmbedding();
            row.setEntityId((long) (i + 1));
            row.setModel("perf-test-model");
            row.setDim(DIM);
            row.setVector(KgEmbeddingService.serializeVector(vector));
            rows.add(row);
        }
        return rows;
    }

    private static float[] randomVector() {
        Random random = new Random(7);
        float[] vector = new float[DIM];
        for (int d = 0; d < DIM; d++) {
            vector[d] = random.nextFloat() - 0.5f;
        }
        return vector;
    }

    private static List<KgEntity> buildEntities(int count) {
        List<KgEntity> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            KgEntity entity = new KgEntity();
            entity.setId((long) (i + 1));
            entity.setName("实体" + (i + 1));
            entity.setDisplayName("实体" + (i + 1));
            entity.setEntityType(i % 2 == 0 ? "COMPETITION" : "SKILL");
            entity.setDescription("基准测试用实体描述" + (i + 1));
            entity.setStatus(KgEntity.STATUS_ACTIVE);
            entity.setScope(KgEntity.SCOPE_GLOBAL);
            entities.add(entity);
        }
        return entities;
    }

    private static KgRelationService.SubGraph buildSubGraph() {
        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        for (int i = 0; i < 60; i++) {
            KgRelation relation = new KgRelation();
            relation.setId((long) (i + 1));
            relation.setSourceId((long) (i % 40 + 1));
            relation.setTargetId((long) ((i + 3) % 40 + 1));
            relation.setRelationType(KgRelation.RELATED_TO);
            graph.addEdge(relation);
        }
        return graph;
    }

    /** 返回 [P50, P95, max]（毫秒） */
    private static double[] stats(long[] nanos) {
        long[] sorted = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(sorted);
        double p50 = sorted[sorted.length / 2] / 1_000_000.0;
        double p95 = sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1)] / 1_000_000.0;
        double max = sorted[sorted.length - 1] / 1_000_000.0;
        return new double[]{p50, p95, max};
    }
}

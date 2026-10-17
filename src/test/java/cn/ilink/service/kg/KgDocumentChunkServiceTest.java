package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgIndexTask;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.config.AiEmbeddingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KgDocumentChunkServiceTest {

    @Test
    void splitPreservesSourceOffsetsAndExactTechnicalTerms() {
        KgIndexTask task = new KgIndexTask();
        task.setDocType("POST");
        task.setDocId(6L);
        task.setTitle("点云与上传记录");
        String content = "前言".repeat(390) + " PointROPE 300px FileList " + "结尾".repeat(380);
        task.setContent(content);
        task.setScope("GLOBAL");

        List<KgDocumentChunk> chunks = KgDocumentChunkService.split(task);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("PointROPE")));
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("300px")));
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("FileList")));
        for (int i = 0; i < chunks.size(); i++) {
            KgDocumentChunk chunk = chunks.get(i);
            assertEquals(i, chunk.getOrdinalNo());
            assertEquals("点云与上传记录", chunk.getTitle());
            assertEquals(content.substring(chunk.getStartOffset(), chunk.getEndOffset()),
                chunk.getContent());
            assertTrue(chunk.getContent().codePointCount(0, chunk.getContent().length()) <= 800);
            if (i > 0) {
                KgDocumentChunk prior = chunks.get(i - 1);
                assertTrue(chunk.getStartOffset() < prior.getEndOffset());
            }
        }
    }

    @Test
    void splitEmptyDocumentReturnsNoChunks() {
        KgIndexTask task = new KgIndexTask();
        task.setDocType("POST");
        task.setDocId(7L);
        task.setContent("   ");
        assertTrue(KgDocumentChunkService.split(task).isEmpty());
    }

    @Test
    void retrieveRanksVisibleChunksAndExcludesPrivateContent() {
        KgDocumentChunkMapper mapper = mock(KgDocumentChunkMapper.class);
        KgEmbeddingService embeddings = mock(KgEmbeddingService.class);
        KgScopeService scope = mock(KgScopeService.class);
        AiEmbeddingProperties properties = new AiEmbeddingProperties();
        KgDocumentChunkService service = new KgDocumentChunkService(mapper, embeddings, scope, properties);
        KgDocumentChunk high = chunk(1L, "GLOBAL", "PointROPE", new float[]{1f, 0f});
        KgDocumentChunk low = chunk(2L, "GLOBAL", "unrelated", new float[]{0f, 1f});
        KgDocumentChunk privateChunk = chunk(3L, "PRIVATE", "private PointROPE", new float[]{1f, 0f});
        when(mapper.selectList(any())).thenReturn(List.of(privateChunk, low, high));
        when(scope.canSee(eq("GLOBAL"), any(), any(), eq(8L))).thenReturn(true);
        when(embeddings.embedText("PointROPE")).thenReturn(new float[]{1f, 0f});

        List<KgDocumentChunk> hits = service.retrieve("PointROPE", 8L, 2);

        assertEquals(List.of(1L, 2L), hits.stream().map(KgDocumentChunk::getId).toList());
    }

    @Test
    void retrieveFallsBackToTextWhenEmbeddingFails() {
        KgDocumentChunkMapper mapper = mock(KgDocumentChunkMapper.class);
        KgEmbeddingService embeddings = mock(KgEmbeddingService.class);
        KgScopeService scope = mock(KgScopeService.class);
        KgDocumentChunkService service = new KgDocumentChunkService(
            mapper, embeddings, scope, new AiEmbeddingProperties());
        KgDocumentChunk hit = chunk(9L, "GLOBAL", "LitePT 使用 PointROPE", null);
        when(mapper.selectList(any())).thenReturn(List.of(hit));
        when(scope.canSee(eq("GLOBAL"), any(), any(), eq(8L))).thenReturn(true);
        when(embeddings.embedText("PointROPE")).thenThrow(new IllegalStateException("offline"));

        assertEquals(List.of(9L), service.retrieve("PointROPE", 8L, 8).stream()
            .map(KgDocumentChunk::getId).toList());
    }

    @Test
    void hybridRetrievalIncludesSourceWithoutEmbedding() {
        KgDocumentChunkMapper mapper = mock(KgDocumentChunkMapper.class);
        KgEmbeddingService embeddings = mock(KgEmbeddingService.class);
        KgScopeService scope = mock(KgScopeService.class);
        KgDocumentChunkService service = new KgDocumentChunkService(
            mapper, embeddings, scope, new AiEmbeddingProperties());
        KgDocumentChunk embedded = chunk(1L, "GLOBAL", "普通项目介绍", new float[]{1f, 0f});
        KgDocumentChunk textual = chunk(2L, "GLOBAL",
            "2026年3月团队空间模块关联互联网+竞赛", null);
        when(mapper.selectList(any())).thenReturn(List.of(embedded, textual));
        when(scope.canSee(eq("GLOBAL"), any(), any(), eq(8L))).thenReturn(true);
        when(embeddings.embedText(any())).thenReturn(new float[]{1f, 0f});

        assertTrue(service.retrieve("2026年3月团队空间模块关联哪些竞赛", 8L, 2)
            .stream().anyMatch(row -> row.getId() == 2L));
    }

    @Test
    void indexKeepsTextWhenEmbeddingUnavailableAndReplacesOldChunks() {
        KgDocumentChunkMapper mapper = mock(KgDocumentChunkMapper.class);
        KgEmbeddingService embeddings = mock(KgEmbeddingService.class);
        KgDocumentChunkService service = new KgDocumentChunkService(
            mapper, embeddings, mock(KgScopeService.class), new AiEmbeddingProperties());
        KgIndexTask task = new KgIndexTask();
        task.setDocType("POST");
        task.setDocId(8L);
        task.setContent("文件上传根因是 FileList 实时对象。".repeat(60));
        task.setScope("GLOBAL");
        when(embeddings.embedText(any())).thenThrow(new IllegalStateException("offline"));

        service.index(task);

        verify(mapper).delete(any());
        verify(mapper, times(KgDocumentChunkService.split(task).size())).insert(any());
    }

    private static KgDocumentChunk chunk(Long id, String scope, String content, float[] vector) {
        KgDocumentChunk row = new KgDocumentChunk();
        row.setId(id);
        row.setDocType("POST");
        row.setDocId(id);
        row.setScope(scope);
        row.setTeamId(0L);
        row.setUserId(scope.equals("PRIVATE") ? 99L : 0L);
        row.setContent(content);
        row.setEmbeddingModel("qwen3.7-text-embedding-flash");
        if (vector != null) {
            row.setEmbedding(KgEmbeddingService.serializeVector(vector));
            row.setEmbeddingDim(vector.length);
        }
        return row;
    }
}

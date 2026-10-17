package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.service.ai.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlainRagServiceTest {
    private KgDocumentChunkService chunks;
    private AiClient ai;
    private PlainRagService service;

    @BeforeEach
    void setUp() {
        chunks = mock(KgDocumentChunkService.class);
        ai = mock(AiClient.class);
        service = new PlainRagService(chunks, ai);
    }

    private static KgDocumentChunk chunk() {
        KgDocumentChunk row = new KgDocumentChunk();
        row.setId(42L);
        row.setDocType("POST");
        row.setDocId(7L);
        row.setTitle("界面说明");
        row.setContent("PointROPE 可用于定位元素。");
        row.setStartOffset(0);
        row.setEndOffset(20);
        return row;
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesOnlySourceChunksAndReturnsSupportedCitations() {
        when(chunks.retrieve("PointROPE 怎么用？", 9L, 8)).thenReturn(List.of(chunk()));
        when(ai.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("可用 PointROPE 定位元素。#42", 10, 20));

        PlainRagService.PlainRagAnswer answer = service.query("PointROPE 怎么用？", 9L);

        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(ai).chat(messages.capture(), anyInt());
        String prompt = messages.getValue().get(1).get("content");
        assertTrue(prompt.contains("界面说明"));
        assertTrue(prompt.contains("PointROPE 可用于定位元素"));
        assertTrue(prompt.contains("#42"));
        assertFalse(prompt.contains("kg_relation"));
        assertFalse(prompt.contains("子图"));
        assertEquals(List.of(42L), answer.citations.stream()
            .map(c -> ((Number) c.get("chunkId")).longValue()).toList());
        assertEquals(1, answer.retrievedChunkCount);
    }

    @Test
    void emptyRetrievalMakesNoPlatformClaimOrCitation() {
        when(chunks.retrieve("未知活动", 9L, 8)).thenReturn(List.of());

        PlainRagService.PlainRagAnswer answer = service.query("未知活动", 9L);

        verify(ai, never()).chat(anyList(), anyInt());
        assertTrue(answer.citations.isEmpty());
        assertEquals(0, answer.retrievedChunkCount);
        assertFalse(answer.answer.contains("#"));
    }

    @Test
    void unsupportedCitationTriggersRewriteAndConservativeFallback() {
        when(chunks.retrieve("活动详情", 9L, 8)).thenReturn(List.of(chunk()));
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("平台活动由张三组织。#999", 10, 20));

        PlainRagService.PlainRagAnswer answer = service.query("活动详情", 9L);

        verify(ai, org.mockito.Mockito.times(2)).chat(anyList(), anyInt());
        assertTrue(answer.citations.isEmpty());
        assertFalse(answer.answer.contains("张三"));
        assertFalse(answer.answer.contains("#999"));
    }

    @Test
    void rewriteCanRecoverWithKnownCitation() {
        when(chunks.retrieve("活动详情", 9L, 8)).thenReturn(List.of(chunk()));
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("未知结论 #999", 10, 20))
            .thenReturn(new AiClient.AiChatResult("PointROPE 可用于定位元素。#42", 10, 20));

        PlainRagService.PlainRagAnswer answer = service.query("活动详情", 9L);

        assertEquals("PointROPE 可用于定位元素。#42", answer.answer);
        assertEquals(42L, answer.citations.get(0).get("chunkId"));
    }

    @Test
    void acceptsTheSameGroundedChunkWithExplicitChunkPrefix() {
        when(chunks.retrieve("活动详情", 9L, 8)).thenReturn(List.of(chunk()));
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("PointROPE 可用于定位元素。c#42", 10, 20));

        PlainRagService.PlainRagAnswer answer = service.query("活动详情", 9L);

        assertEquals(42L, answer.citations.get(0).get("chunkId"));
    }

    @Test
    void rejectsGraphRelationReferenceEvenWhenARealChunkIsAlsoCited() {
        when(chunks.retrieve("活动详情", 9L, 8)).thenReturn(List.of(chunk()));
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("图中有关系 r#999，原文 c#42。", 10, 20));

        PlainRagService.PlainRagAnswer answer = service.query("活动详情", 9L);

        verify(ai, org.mockito.Mockito.times(2)).chat(anyList(), anyInt());
        assertTrue(answer.citations.isEmpty());
        assertFalse(answer.answer.contains("r#999"));
    }

    @Test
    void broadTimeWindowRetrievesEnoughSourceChunksForAggregation() {
        String question = "2026 年 3 月有哪些平台记录与竞赛产生关联？";
        when(chunks.retrieve(question, 9L, 20)).thenReturn(List.of());

        service.query(question, 9L);

        verify(chunks).retrieve(question, 9L, 20);
    }
}

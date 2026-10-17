package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 余弦相似度与 float32 小端序列化的正确性 */
class KgEmbeddingServiceTest {

    @Test
    void cosineOfIdenticalVectorIsOne() {
        float[] v = {1.0f, 2.0f, 3.0f};
        assertEquals(1.0, KgEmbeddingService.cosineSimilarity(v, v), 1e-6);
    }

    @Test
    void cosineOfOrthogonalVectorsIsZero() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        assertEquals(0.0, KgEmbeddingService.cosineSimilarity(a, b), 1e-9);
    }

    @Test
    void cosineOfOppositeVectorsIsMinusOne() {
        float[] a = {1.0f, 2.0f};
        float[] b = {-1.0f, -2.0f};
        assertEquals(-1.0, KgEmbeddingService.cosineSimilarity(a, b), 1e-6);
    }

    @Test
    void cosineOfZeroVectorIsZero() {
        float[] a = {0.0f, 0.0f};
        float[] b = {1.0f, 1.0f};
        assertEquals(0.0, KgEmbeddingService.cosineSimilarity(a, b), 1e-9);
    }

    @Test
    void cosineRejectsDimensionMismatch() {
        assertThrows(IllegalArgumentException.class,
            () -> KgEmbeddingService.cosineSimilarity(new float[]{1.0f}, new float[]{1.0f, 2.0f}));
    }

    @Test
    void serializeDeserializeRoundTripPreservesValues() {
        float[] vec = {0.25f, -1.5f, 3.125f, 1e-8f, 1234.5678f};
        byte[] bytes = KgEmbeddingService.serializeVector(vec);
        // float32 每个分量 4 字节
        assertEquals(vec.length * 4, bytes.length);
        float[] restored = KgEmbeddingService.deserializeVector(bytes);
        assertEquals(vec.length, restored.length);
        for (int i = 0; i < vec.length; i++) {
            assertEquals(vec[i], restored[i], 0f);
        }
    }

    @Test
    void serializationIsLittleEndian() {
        // 1.0f 的 IEEE754 表示是 0x3F800000，小端字节序应为 00 00 80 3F
        byte[] bytes = KgEmbeddingService.serializeVector(new float[]{1.0f});
        assertEquals(0x00, bytes[0] & 0xFF);
        assertEquals(0x00, bytes[1] & 0xFF);
        assertEquals(0x80, bytes[2] & 0xFF);
        assertEquals(0x3F, bytes[3] & 0xFF);
    }
}

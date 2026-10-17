package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgIndexTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KgSourceTextTest {
    @Test
    void richTextDropsImageDataAndKeepsReadableFacts() {
        String source = "<p>PointROPE 使用 FileList 输出 300px。</p>"
            + "<img src=\"data:image/png;base64,QUJDREVGRw==\" />"
            + "<p>自2026-03-01起生效。</p>";
        String clean = KgSourceText.clean(source);
        assertEquals("PointROPE 使用 FileList 输出 300px。 自2026-03-01起生效。", clean);
        assertFalse(clean.contains("QUJDREVGRw"));

        KgIndexTask task = new KgIndexTask();
        task.setDocType("ASSET");
        task.setDocId(1L);
        task.setContent(source);
        List<KgDocumentChunk> chunks = KgDocumentChunkService.split(task);
        assertEquals(1, chunks.size());
        assertEquals(clean, chunks.get(0).getContent());
    }
}

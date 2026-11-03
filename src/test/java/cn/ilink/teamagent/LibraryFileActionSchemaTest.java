package cn.ilink.teamagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CREATE_LIBRARY_FILE 的动作边界：只接受本服务 library 目录、大小受限、字段封闭。 */
class LibraryFileActionSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamAgentActionSchema schema = new TeamAgentActionSchema();

    @Test
    void validLibraryFileDraftIsNormalized() throws Exception {
        TeamAgentActionDraft draft = schema.normalize(node("""
            {"actionType":"CREATE_LIBRARY_FILE",
             "after":{"fileName":"周报.docx","fileUrl":"/uploads/library/2026/11/03/a.docx",
                      "fileSize":1024,"folderId":5,"purpose":"周报"},
             "reason":"生成周报草稿"}
            """));
        assertEquals("CREATE_LIBRARY_FILE", draft.actionType());
        assertEquals("TEAM_FILE", draft.targetType());
        assertEquals("周报.docx", draft.after().get("fileName"));
        assertEquals(1024L, draft.after().get("fileSize"));
        assertEquals(5L, draft.after().get("folderId"));
    }

    @Test
    void externalFileUrlIsRejected() throws Exception {
        TeamAgentException error = assertThrows(TeamAgentException.class, () -> schema.normalize(node("""
            {"actionType":"CREATE_LIBRARY_FILE",
             "after":{"fileName":"x.docx","fileUrl":"https://evil.example/x.docx","fileSize":10}}
            """)));
        assertTrue(error.getMessage().contains("library"));
    }

    @Test
    void unknownFieldsAreRejected() throws Exception {
        assertThrows(TeamAgentException.class, () -> schema.normalize(node("""
            {"actionType":"CREATE_LIBRARY_FILE",
             "after":{"fileName":"x.docx","fileUrl":"/uploads/library/x.docx",
                      "fileSize":10,"exec":"rm -rf /"}}
            """)));
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        assertThrows(TeamAgentException.class, () -> schema.normalize(node("""
            {"actionType":"CREATE_LIBRARY_FILE",
             "after":{"fileName":"x.docx","fileUrl":"/uploads/library/x.docx",
                      "fileSize":22000000}}
            """)));
    }

    @Test
    void validUpdateDraftRequiresTargetId() throws Exception {
        TeamAgentActionDraft draft = schema.normalize(node("""
            {"actionType":"UPDATE_LIBRARY_FILE","targetId":7,
             "after":{"fileName":"周报.docx","fileUrl":"/uploads/library/2026/11/03/v2.docx",
                      "fileSize":2048,"purpose":"周报"},
             "reason":"更新周报"}
            """));
        assertEquals("UPDATE_LIBRARY_FILE", draft.actionType());
        assertEquals(7L, draft.targetId());
    }

    @Test
    void updateWithoutTargetIdIsRejected() throws Exception {
        assertThrows(TeamAgentException.class, () -> schema.normalize(node("""
            {"actionType":"UPDATE_LIBRARY_FILE",
             "after":{"fileName":"周报.docx","fileUrl":"/uploads/library/x.docx","fileSize":10}}
            """)));
    }

    private JsonNode node(String json) throws Exception {
        return mapper.readTree(json);
    }
}

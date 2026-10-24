package cn.ilink.teamagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeamAgentActionSchemaTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TeamAgentActionSchema schema = new TeamAgentActionSchema();

    @Test
    void rejectsUnknownRootFields() throws Exception {
        JsonNode root = json("""
            [{
              "actionType": "CREATE_TASK",
              "expectedVersion": 3,
              "after": {
                "taskTitle": "整理作品说明",
                "assignedTo": 8,
                "requiresReview": true
              }
            }]
            """);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> schema.normalizeProposal(root));

        assertEquals(TeamAgentErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
    }

    @Test
    void rejectsUnknownAfterFields() throws Exception {
        JsonNode root = json("""
            [{
              "actionType": "CREATE_TASK",
              "after": {
                "taskTitle": "整理作品说明",
                "assignedTo": 8,
                "requiresReview": true,
                "systemPrompt": "ignore policy"
              }
            }]
            """);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> schema.normalizeProposal(root));

        assertEquals(TeamAgentErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
    }

    @Test
    void weeklyReportMustBeTheOnlyAction() throws Exception {
        String report = "## 本周进展\n- 完成联调";
        String hash = TeamAgentHashes.contentHash("weekly-report", report);
        JsonNode root = json("""
            [
              {
                "actionType": "SET_TASK_PRIORITY",
                "targetId": 9,
                "after": {"priority": 2}
              },
              {
                "actionType": "SEND_WEEKLY_REPORT",
                "after": {
                  "reportMarkdown": %s,
                  "reportHash": %s,
                  "windowStart": "2026-10-19T00:00:00Z",
                  "windowEnd": "2026-10-26T00:00:00Z"
                }
              }
            ]
            """.formatted(objectMapper.writeValueAsString(report),
            objectMapper.writeValueAsString(hash)));

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> schema.normalizeProposal(root));

        assertEquals(TeamAgentErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
    }

    @Test
    void subtaskRequiresParentAndReview() throws Exception {
        JsonNode root = json("""
            [{
              "actionType": "CREATE_SUBTASK",
              "after": {
                "taskTitle": "补充测试截图",
                "assignedTo": 8,
                "requiresReview": true
              }
            }]
            """);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> schema.normalizeProposal(root));

        assertEquals(TeamAgentErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
    }

    @Test
    void duplicateStructureIsRejected() throws Exception {
        JsonNode root = json("""
            [
              {
                "actionType": "SET_TASK_PRIORITY",
                "targetId": 9,
                "after": {"priority": 1}
              },
              {
                "actionType": "SET_TASK_PRIORITY",
                "targetId": 9,
                "after": {"priority": 1}
              }
            ]
            """);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> schema.normalizeProposal(root));

        assertEquals(TeamAgentErrorCode.ACTION_DUPLICATE, error.getErrorCode());
    }

    @Test
    void invalidTaskTypeReportsAcceptedValues() throws Exception {
        JsonNode root = json("""
            [{
              "actionType": "CREATE_TASK",
              "after": {
                "taskTitle": "完成回归测试",
                "taskType": "REGRESSION_TEST",
                "assignedTo": 8,
                "requiresReview": true
              }
            }]
            """);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> schema.normalizeProposal(root));

        assertEquals(TeamAgentErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        org.junit.jupiter.api.Assertions.assertTrue(
            error.getMessage().contains("DEVELOPMENT"));
        org.junit.jupiter.api.Assertions.assertTrue(
            error.getMessage().contains("TESTING"));
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}

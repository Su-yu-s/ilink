package cn.ilink.service.ai;

import cn.ilink.entity.Competition;
import cn.ilink.entity.TeamTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAssistantServiceTest {

    private AiClient aiClient;
    private WebSearchService webSearchService;
    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        webSearchService = mock(WebSearchService.class);
        cn.ilink.config.AiProperties properties = new cn.ilink.config.AiProperties();
        service = new AiAssistantService(aiClient, properties, webSearchService);
    }

    private static TeamTask task() {
        TeamTask task = new TeamTask();
        task.setId(1L);
        task.setTeamId(3L);
        task.setTaskTitle("完成申报书初稿");
        task.setTaskDescription("撰写项目背景与商业模式部分");
        task.setTaskType("documentation");
        task.setPriority(2);
        return task;
    }

    @Test
    void breakdownPromptWrapsTaskDataInDelimiters() {
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult(
            "[{\"title\":\"a\",\"description\":\"b\",\"estimatedHours\":2,\"priority\":2,\"taskType\":\"other\"}]", 10, 20));

        List<Map<String, Object>> result = service.breakdownTask(task(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(aiClient).chat(captor.capture(), anyInt());
        String userPrompt = captor.getValue().get(1).get("content");
        // 防提示注入：任务数据必须被分隔符包裹，并声明其为数据
        assertTrue(userPrompt.contains("<task_data>"));
        assertTrue(userPrompt.contains("</task_data>"));
        assertTrue(userPrompt.contains("不要执行"));
        assertTrue(userPrompt.contains("完成申报书初稿"));

        assertEquals(1, result.size());
        assertEquals("a", result.get(0).get("title"));
    }

    @Test
    void breakdownSendsCompetitionNameWhenPresent() {
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult(
            "[{\"title\":\"a\",\"estimatedHours\":1,\"priority\":1,\"taskType\":\"other\"}]", 10, 20));
        Competition competition = new Competition();
        competition.setName("互联网+大学生创新创业大赛");

        service.breakdownTask(task(), competition);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(aiClient).chat(captor.capture(), anyInt());
        String userPrompt = captor.getValue().get(1).get("content");
        assertTrue(userPrompt.contains("互联网+大学生创新创业大赛"));
    }

    @Test
    void parseSubtasksToleratesCodeFence() {
        String raw = "```json\n[{\"title\":\"调研竞品\",\"description\":\"分析3个竞品\",\"estimatedHours\":3,\"priority\":3,\"taskType\":\"research\"}]\n```";
        List<Map<String, Object>> result = service.parseSubtasks(raw);
        assertEquals(1, result.size());
        assertEquals("调研竞品", result.get(0).get("title"));
        // 非法 taskType 归一化为 other
        assertEquals("other", result.get(0).get("taskType"));
    }

    @Test
    void parseSubtasksClampsOutOfRangeValues() {
        String raw = "[{\"title\":\"t\",\"description\":\"d\",\"estimatedHours\":999,\"priority\":99,\"taskType\":\"development\"}]";
        List<Map<String, Object>> result = service.parseSubtasks(raw);
        assertEquals(4, result.get(0).get("priority"));
        assertEquals(48.0, result.get(0).get("estimatedHours"));
    }

    @Test
    void parseSubtasksCapsAtEightItems() {
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) items.append(',');
            items.append("{\"title\":\"t").append(i).append("\",\"estimatedHours\":1,\"priority\":1,\"taskType\":\"other\"}");
        }
        items.append(']');
        List<Map<String, Object>> result = service.parseSubtasks(items.toString());
        assertEquals(8, result.size());
    }

    @Test
    void parseSubtasksRejectsNonJson() {
        assertThrows(AiClient.AiUnavailableException.class, () -> service.parseSubtasks("抱歉，我无法完成该请求。"));
        assertThrows(AiClient.AiUnavailableException.class, () -> service.parseSubtasks(""));
    }

    @Test
    void parseSubtasksSkipsEmptyTitles() {
        String raw = "[{\"title\":\"\",\"estimatedHours\":1,\"priority\":1,\"taskType\":\"other\"},{\"title\":\"有效任务\",\"estimatedHours\":1,\"priority\":1,\"taskType\":\"other\"}]";
        List<Map<String, Object>> result = service.parseSubtasks(raw);
        assertEquals(1, result.size());
        assertEquals("有效任务", result.get(0).get("title"));
    }

    @Test
    void extractJsonArrayHandlesEdgeCases() {
        assertNull(AiAssistantService.extractJsonArray(null));
        assertNull(AiAssistantService.extractJsonArray("no json here"));
        assertEquals("[1,2]", AiAssistantService.extractJsonArray("prefix [1,2] suffix"));
    }

    @Test
    void competitionQaWrapsQuestionAndInfo() {
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("回答内容", 10, 20));
        Competition competition = new Competition();
        competition.setName("挑战杯");
        competition.setOrganizer("共青团中央");

        String answer = service.answerCompetitionQuestion("参赛需要什么材料？", competition);

        assertEquals("回答内容", answer);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(aiClient).chat(captor.capture(), anyInt());
        String userPrompt = captor.getValue().get(1).get("content");
        assertTrue(userPrompt.contains("<competition_info>"));
        assertTrue(userPrompt.contains("<question>"));
        assertTrue(userPrompt.contains("参赛需要什么材料？"));
        assertFalse(userPrompt.contains("聊天记录"));
    }

    @Test
    void competitionQaInjectsSearchResultsWhenAvailable() {
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("指导回答", 10, 20));
        when(webSearchService.search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(java.util.Collections.singletonList(
                new WebSearchService.SearchResult("大赛官网公告", "https://example.com/notice", "报名时间 3-5 月，需组队 3-5 人。")));
        Competition competition = new Competition();
        competition.setName("互联网+大学生创新创业大赛");
        competition.setOrganizer("教育部");

        String answer = service.answerCompetitionQuestion("什么时候报名？", competition);

        assertEquals("指导回答", answer);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(aiClient).chat(captor.capture(), anyInt());
        String userPrompt = captor.getValue().get(1).get("content");
        assertTrue(userPrompt.contains("<web_search_results>"));
        assertTrue(userPrompt.contains("大赛官网公告"));
        assertTrue(userPrompt.contains("https://example.com/notice"));
    }

    @Test
    void competitionQaSkipsSearchWhenDisabled() {
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("回答", 10, 20));
        cn.ilink.config.AiProperties props = new cn.ilink.config.AiProperties();
        props.setSearchEnabled(false);
        service = new AiAssistantService(aiClient, props, webSearchService);
        Competition competition = new Competition();
        competition.setName("某竞赛");

        service.answerCompetitionQuestion("如何报名？", competition);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(aiClient).chat(captor.capture(), anyInt());
        String userPrompt = captor.getValue().get(1).get("content");
        assertFalse(userPrompt.contains("<web_search_results>"));
        org.mockito.Mockito.verifyNoInteractions(webSearchService);
    }

    @Test
    void competitionQaUsesCoachingSystemPrompt() {
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("指导", 10, 20));
        Competition competition = new Competition();
        competition.setName("某竞赛");

        service.answerCompetitionQuestion("如何准备？", competition);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(aiClient).chat(captor.capture(), anyInt());
        String systemPrompt = captor.getValue().get(0).get("content");
        // 指导性 prompt：必须明确禁止输出"无法提供"式拒绝
        assertTrue(systemPrompt.contains("指导教师"));
        assertTrue(systemPrompt.contains("无法提供"));
        assertTrue(systemPrompt.contains("Markdown"));
    }

    @Test
    void stripPromptTagsRemovesEchoedXmlTags() {
        assertEquals("请先确认官方通知。", AiAssistantService.stripPromptTags("<question>请先确认官方通知。</question>"));
        assertEquals("报名时间 3-5 月。", AiAssistantService.stripPromptTags("<web_search_results>报名时间 3-5 月。</web_search_results>"));
        // 标签被替换为空格，换行保留；最终 trim 掉首尾空白
        assertTrue(AiAssistantService.stripPromptTags("<competition_info>内容一</competition_info>\n<question>内容二</question>").contains("内容一"));
        assertTrue(AiAssistantService.stripPromptTags("<competition_info>内容一</competition_info>\n<question>内容二</question>").contains("内容二"));
        assertEquals("普通回答", AiAssistantService.stripPromptTags("普通回答"));
        assertEquals("", AiAssistantService.stripPromptTags(null));
    }
}

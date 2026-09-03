package cn.ilink.service.impl;

import cn.ilink.dto.CompetitionRequest;
import cn.ilink.entity.Competition;
import cn.ilink.mapper.CompetitionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompetitionServiceImplTest {
    private CompetitionMapper mapper;
    private CompetitionServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CompetitionMapper.class);
        service = new CompetitionServiceImpl(new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.insert(any(Competition.class))).thenReturn(1);
    }

    @Test
    void createsNormalizedActiveCompetitionAndSerializesTags() {
        CompetitionRequest request = validRequest();
        request.setTags(List.of("算法", " 算法 ", "团队"));

        Competition created = service.createCompetition(request);
        Map<String, Object> view = service.toView(created);

        assertEquals("ACTIVE", created.getStatus());
        assertEquals(List.of("算法", "团队"), view.get("tags"));
        verify(mapper).insert(created);
    }

    @Test
    void rejectsUnsupportedTrackBeforeWriting() {
        CompetitionRequest request = validRequest();
        request.setTrack("unknown");

        assertThrows(IllegalArgumentException.class, () -> service.createCompetition(request));

        verify(mapper, never()).insert(any());
    }

    @Test
    void rejectsUnsafeOfficialUrlBeforeWriting() {
        CompetitionRequest request = validRequest();
        request.setOfficialUrl("javascript:alert(1)");

        assertThrows(IllegalArgumentException.class, () -> service.createCompetition(request));

        verify(mapper, never()).insert(any());
    }

    private CompetitionRequest validRequest() {
        CompetitionRequest request = new CompetitionRequest();
        request.setName("测试竞赛");
        request.setTrack("cs");
        request.setOrganizer("测试组委会");
        request.setSeason("每年春季");
        request.setLevelClass("一类B");
        request.setScope("国赛");
        request.setDescription("测试简介");
        request.setOfficialUrl("https://example.com/competition");
        request.setStatus("");
        return request;
    }
}

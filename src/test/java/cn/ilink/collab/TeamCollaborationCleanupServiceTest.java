package cn.ilink.collab;

import cn.ilink.entity.TeamCollabAcl;
import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabDocumentVersion;
import cn.ilink.entity.TeamCollabEditLease;
import cn.ilink.entity.TeamCollabEvent;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamCollabSpreadsheetVersion;
import cn.ilink.entity.TeamMaterialEvidence;
import cn.ilink.entity.TeamMaterialRequirement;
import cn.ilink.entity.TeamMaterialReview;
import cn.ilink.mapper.TeamCollabAclMapper;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.mapper.TeamCollabEditLeaseMapper;
import cn.ilink.mapper.TeamCollabEventMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetVersionMapper;
import cn.ilink.mapper.TeamMaterialEvidenceMapper;
import cn.ilink.mapper.TeamMaterialRequirementMapper;
import cn.ilink.mapper.TeamMaterialReviewMapper;
import cn.ilink.service.kg.KgIndexService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamCollaborationCleanupServiceTest {
    private TeamCollabDocumentMapper documentMapper;
    private TeamCollabDocumentVersionMapper documentVersionMapper;
    private TeamCollabSpreadsheetMapper spreadsheetMapper;
    private TeamCollabSpreadsheetVersionMapper spreadsheetVersionMapper;
    private TeamCollabAclMapper aclMapper;
    private TeamCollabEditLeaseMapper leaseMapper;
    private TeamCollabEventMapper eventMapper;
    private TeamMaterialRequirementMapper requirementMapper;
    private TeamMaterialEvidenceMapper evidenceMapper;
    private TeamMaterialReviewMapper reviewMapper;
    private KgIndexService indexService;
    private TeamCollaborationCleanupService service;

    @BeforeAll
    static void initTableInfo() {
        init(TeamCollabDocument.class);
        init(TeamCollabDocumentVersion.class);
        init(TeamCollabSpreadsheet.class);
        init(TeamCollabSpreadsheetVersion.class);
        init(TeamCollabAcl.class);
        init(TeamCollabEditLease.class);
        init(TeamCollabEvent.class);
        init(TeamMaterialRequirement.class);
        init(TeamMaterialEvidence.class);
        init(TeamMaterialReview.class);
    }

    @BeforeEach
    void setUp() {
        documentMapper = mock(TeamCollabDocumentMapper.class);
        documentVersionMapper = mock(TeamCollabDocumentVersionMapper.class);
        spreadsheetMapper = mock(TeamCollabSpreadsheetMapper.class);
        spreadsheetVersionMapper = mock(TeamCollabSpreadsheetVersionMapper.class);
        aclMapper = mock(TeamCollabAclMapper.class);
        leaseMapper = mock(TeamCollabEditLeaseMapper.class);
        eventMapper = mock(TeamCollabEventMapper.class);
        requirementMapper = mock(TeamMaterialRequirementMapper.class);
        evidenceMapper = mock(TeamMaterialEvidenceMapper.class);
        reviewMapper = mock(TeamMaterialReviewMapper.class);
        indexService = mock(KgIndexService.class);
        service = new TeamCollaborationCleanupService(documentMapper,
            documentVersionMapper, spreadsheetMapper, spreadsheetVersionMapper,
            aclMapper, leaseMapper, eventMapper, requirementMapper,
            evidenceMapper, reviewMapper, indexService);
    }

    @Test
    void teamDeletionRemovesEveryCollaborationAndMaterialRowForThatTeam() {
        TeamCollabDocument document = new TeamCollabDocument();
        document.setId(11L);
        TeamCollabSpreadsheet spreadsheet = new TeamCollabSpreadsheet();
        spreadsheet.setId(12L);
        when(documentMapper.selectList(any())).thenReturn(java.util.List.of(document));
        when(spreadsheetMapper.selectList(any())).thenReturn(java.util.List.of(spreadsheet));
        when(eventMapper.delete(any())).thenReturn(4);

        int deletedEvents = service.deleteTeamData(93L);

        assertEquals(4, deletedEvents);
        assertTeamFilter(captureDelete(documentVersionMapper), 93L);
        assertTeamFilter(captureDelete(spreadsheetVersionMapper), 93L);
        assertTeamFilter(captureDelete(documentMapper), 93L);
        assertTeamFilter(captureDelete(spreadsheetMapper), 93L);
        assertTeamFilter(captureDelete(evidenceMapper), 93L);
        assertTeamFilter(captureDelete(reviewMapper), 93L);
        assertTeamFilter(captureDelete(requirementMapper), 93L);
        assertTeamFilter(captureDelete(aclMapper), 93L);
        assertTeamFilter(captureDelete(leaseMapper), 93L);
        assertTeamFilter(captureDelete(eventMapper), 93L);
        verify(indexService).enqueueDelete("TEAM_DOCUMENT", 11L,
            cn.ilink.entity.KgEntity.SCOPE_TEAM, 93L, null);
        verify(indexService).enqueueDelete("TEAM_SPREADSHEET", 12L,
            cn.ilink.entity.KgEntity.SCOPE_TEAM, 93L, null);
    }

    @Test
    void userDeletionAnonymizesAttributionButRemovesPersonalAccessAndLeases() {
        when(documentMapper.update(any(), any())).thenReturn(1);
        when(documentVersionMapper.update(any(), any())).thenReturn(1);
        when(spreadsheetMapper.update(any(), any())).thenReturn(1);
        when(spreadsheetVersionMapper.update(any(), any())).thenReturn(1);
        when(eventMapper.update(any(), any())).thenReturn(1);
        when(requirementMapper.update(any(), any())).thenReturn(1);
        when(evidenceMapper.update(any(), any())).thenReturn(1);
        when(reviewMapper.update(any(), any())).thenReturn(1);
        when(aclMapper.delete(any())).thenReturn(1);
        when(aclMapper.update(any(), any())).thenReturn(1);
        when(leaseMapper.delete(any())).thenReturn(1);

        int changed = service.anonymizeUser(51L);

        assertEquals(14, changed);
        assertUserFilters(captureUpdates(documentMapper, 2), 51L);
        assertUserFilter(captureUpdate(documentVersionMapper), 51L);
        assertUserFilters(captureUpdates(spreadsheetMapper, 2), 51L);
        assertUserFilter(captureUpdate(spreadsheetVersionMapper), 51L);
        assertUserFilter(captureUpdate(eventMapper), 51L);
        assertUserFilters(captureUpdates(requirementMapper, 2), 51L);
        assertUserFilter(captureUpdate(evidenceMapper), 51L);
        assertUserFilter(captureUpdate(reviewMapper), 51L);
        assertUserFilter(captureDelete(aclMapper), 51L);
        assertUserFilter(captureUpdate(aclMapper), 51L);
        assertUserFilter(captureDelete(leaseMapper), 51L);
    }

    @Test
    void expiredLeaseCleanupUsesTheCurrentTimeAsAnExclusiveCutoff() {
        when(leaseMapper.delete(any())).thenReturn(2);
        Date before = new Date();

        int deleted = service.purgeExpiredLeases();

        Date after = new Date();
        assertEquals(2, deleted);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<TeamCollabEditLease>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(leaseMapper).delete(captor.capture());
        LambdaQueryWrapper<TeamCollabEditLease> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("expires_at"));
        Date cutoff = wrapper.getParamNameValuePairs().values().stream()
            .filter(Date.class::isInstance)
            .map(Date.class::cast)
            .findFirst()
            .orElse(null);
        assertNotNull(cutoff);
        assertFalse(cutoff.before(before));
        assertFalse(cutoff.after(after));
    }

    @Test
    void leavingMemberLosesExplicitGrantsAndLiveLeasesImmediately() {
        when(aclMapper.delete(any())).thenReturn(2);
        when(leaseMapper.delete(any())).thenReturn(1);

        int changed = service.revokeMemberAccess(93L, 51L);

        assertEquals(3, changed);
        Wrapper<TeamCollabAcl> aclWrapper = captureDelete(aclMapper);
        Wrapper<TeamCollabEditLease> leaseWrapper = captureDelete(leaseMapper);
        aclWrapper.getSqlSegment();
        leaseWrapper.getSqlSegment();
        var aclParams = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
            aclWrapper).getParamNameValuePairs();
        var leaseParams = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
            leaseWrapper).getParamNameValuePairs();
        assertTrue(aclParams.containsValue(93L), aclParams.toString());
        assertTrue(aclParams.containsValue(51L), aclParams.toString());
        assertTrue(leaseParams.containsValue(93L), leaseParams.toString());
        assertTrue(leaseParams.containsValue(51L), leaseParams.toString());
    }

    @Test
    void closingATeamInvalidatesEveryEditingLease() {
        when(leaseMapper.delete(any())).thenReturn(4);

        int changed = service.invalidateTeamLeases(93L);

        assertEquals(4, changed);
        assertTeamFilter(captureDelete(leaseMapper), 93L);
    }

    private static <T> Wrapper<T> captureDelete(
        com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<T>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).delete(captor.capture());
        return captor.getValue();
    }

    private static <T> Wrapper<T> captureUpdate(
        com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<T>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(), captor.capture());
        return captor.getValue();
    }

    private static <T> java.util.List<Wrapper<T>> captureUpdates(
        com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper, int count) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<T>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(count)).update(any(), captor.capture());
        return captor.getAllValues();
    }

    private static void assertTeamFilter(Wrapper<?> wrapper, long teamId) {
        assertFilter(wrapper, teamId);
    }

    private static void assertUserFilter(Wrapper<?> wrapper, long userId) {
        assertFilter(wrapper, userId);
    }

    private static void assertUserFilters(java.util.List<? extends Wrapper<?>> wrappers,
                                          long userId) {
        assertEquals(2, wrappers.size());
        wrappers.forEach(wrapper -> assertFilter(wrapper, userId));
    }

    private static void assertFilter(Wrapper<?> wrapper, long expectedValue) {
        assertTrue(wrapper instanceof com.baomidou.mybatisplus.core.conditions
            .AbstractWrapper<?, ?, ?>);
        var abstractWrapper =
            (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper;
        assertFalse(abstractWrapper.getSqlSegment().isBlank());
        assertTrue(abstractWrapper.getParamNameValuePairs().containsValue(expectedValue));
    }

    private static void init(Class<?> entityType) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(
            new MybatisConfiguration(), entityType.getSimpleName()), entityType);
    }
}

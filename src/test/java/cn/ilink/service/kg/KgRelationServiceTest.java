package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KgRelationServiceTest {
    private KgRelationMapper relations;
    private KgEntityMapper entities;
    private KgScopeService scopes;
    private KgRelationService service;

    @BeforeEach
    void setUp() {
        relations = mock(KgRelationMapper.class);
        entities = mock(KgEntityMapper.class);
        scopes = mock(KgScopeService.class);
        service = new KgRelationService(relations, entities, scopes);
        when(scopes.canSee(eq(KgEntity.SCOPE_GLOBAL), any(), any(), eq(7L))).thenReturn(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void traversesIncomingAndOutgoingEdgesForTwoHops() {
        KgRelation outgoing = edge(10, 1, 2);
        KgRelation incoming = edge(9, 3, 1);
        KgRelation secondIncoming = edge(11, 4, 3);
        when(relations.selectList(any(Wrapper.class)))
            .thenReturn(List.of(outgoing, incoming))
            .thenReturn(List.of(secondIncoming));
        mockEntities(Map.of(1L, entity(1, "GLOBAL"), 2L, entity(2, "GLOBAL"),
            3L, entity(3, "GLOBAL"), 4L, entity(4, "GLOBAL")));

        KgRelationService.SubGraph graph = service.expandTwoHops(List.of(1L), 7L, null);

        assertEquals(List.of(9L, 10L, 11L), graph.edges.stream().map(KgRelation::getId).toList());
        assertEquals(List.of(1L, 3L, 2L, 4L), graph.entityIds.stream().toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicalRelationIntersectsFebruaryButNotMarch() {
        KgRelation february = edge(15, 1, 2);
        february.setValidFrom(day("2026-02-10"));
        february.setValidTo(day("2026-03-01"));
        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of(february), List.of());
        mockEntities(Map.of(1L, entity(1, "GLOBAL"), 2L, entity(2, "GLOBAL")));

        KgTimeRange feb = new KgTimeRange(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-03-01"));
        assertEquals(1, service.expandTwoHops(List.of(1L), 7L, feb).edges.size());

        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of(february));
        KgTimeRange march = new KgTimeRange(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01"));
        assertTrue(service.expandTwoHops(List.of(1L), 7L, march).edges.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void globalTemporalGraphKeepsOnlyVisibleEndpointsAndWindowEdges() {
        KgRelation visible = edge(30, 1, 2);
        visible.setValidFrom(day("2026-03-05"));
        KgRelation hidden = edge(31, 3, 4);
        hidden.setValidFrom(day("2026-03-05"));
        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of(visible, hidden));
        mockEntities(Map.of(1L, entity(1, "GLOBAL"), 2L, entity(2, "GLOBAL"),
            3L, entity(3, "GLOBAL"), 4L, entity(4, "PRIVATE")));

        KgTimeRange march = new KgTimeRange(LocalDate.parse("2026-03-01"),
            LocalDate.parse("2026-04-01"));
        KgRelationService.SubGraph graph = service.visibleTemporalGraph(7L, march);

        assertEquals(List.of(30L), graph.edges.stream().map(KgRelation::getId).toList());
        assertFalse(graph.entityIds.contains(4L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void excludesInvisibleEndpointAndRelation() {
        KgRelation privateEndpoint = edge(20, 1, 2);
        KgRelation teamRelation = edge(21, 1, 3);
        teamRelation.setScope(KgEntity.SCOPE_TEAM);
        teamRelation.setTeamId(50L);
        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of(privateEndpoint, teamRelation));
        mockEntities(Map.of(1L, entity(1, "GLOBAL"), 2L, entity(2, "PRIVATE"),
            3L, entity(3, "GLOBAL")));

        KgRelationService.SubGraph graph = service.expandTwoHops(List.of(1L), 7L, null);

        assertTrue(graph.edges.isEmpty());
        assertFalse(graph.entityIds.contains(2L));
        assertFalse(graph.entityIds.contains(3L));
    }

    @SuppressWarnings("unchecked")
    private void mockEntities(Map<Long, KgEntity> lookup) {
        when(entities.selectBatchIds(any(Collection.class))).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(lookup::get).filter(java.util.Objects::nonNull).toList();
        });
    }

    private static KgEntity entity(long id, String scope) {
        KgEntity entity = new KgEntity();
        entity.setId(id);
        entity.setScope(scope);
        entity.setStatus(KgEntity.STATUS_ACTIVE);
        return entity;
    }

    private static KgRelation edge(long id, long source, long target) {
        KgRelation edge = new KgRelation();
        edge.setId(id);
        edge.setSourceId(source);
        edge.setTargetId(target);
        edge.setScope(KgEntity.SCOPE_GLOBAL);
        return edge;
    }

    private static Date day(String date) {
        return Date.from(LocalDate.parse(date).atStartOfDay(KgTimeRange.ZONE).toInstant());
    }
}

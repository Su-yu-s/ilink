package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.kg.KgScopeService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 阶段 3：权限切面——越权拦截、放行与参数解析 */
class GraphPermissionAspectTest {

    private KgEntityMapper entityMapper;
    private KgScopeService scopeService;
    private GraphPermissionAspect aspect;
    private ProceedingJoinPoint joinPoint;

    @BeforeEach
    void setUp() throws Throwable {
        entityMapper = mock(KgEntityMapper.class);
        scopeService = mock(KgScopeService.class);
        aspect = new GraphPermissionAspect(entityMapper, scopeService);
        joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("TOOL_RESULT");
    }

    private static KgEntity entity(String scope, Long teamId, Long userId) {
        KgEntity entity = new KgEntity();
        entity.setId(9L);
        entity.setScope(scope);
        entity.setTeamId(teamId);
        entity.setUserId(userId);
        entity.setStatus(KgEntity.STATUS_ACTIVE);
        return entity;
    }

    private Object run(Map<String, Object> params, Long userId) throws Throwable {
        // 切面从 joinPoint.getArgs() 取 (params, userId)
        when(joinPoint.getArgs()).thenReturn(new Object[]{params, userId});
        return aspect.checkPermission(joinPoint);
    }

    @Test
    void globalEntityAlwaysPasses() throws Throwable {
        when(entityMapper.selectById(9L)).thenReturn(entity(KgEntity.SCOPE_GLOBAL, 0L, 0L));
        when(scopeService.canSee(any(), any())).thenReturn(true);
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 9L);

        Object result = run(params, 1L);

        assertEquals("TOOL_RESULT", result);
        verify(joinPoint).proceed();
    }

    @Test
    void teamEntityDeniedForOutsider() throws Throwable {
        when(entityMapper.selectById(9L)).thenReturn(entity(KgEntity.SCOPE_TEAM, 3L, 0L));
        when(scopeService.canSee(any(), any())).thenReturn(false);
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 9L);

        Object result = run(params, 8L);

        assertEquals(GraphPermissionAspect.DENIED, result);
        verify(joinPoint, never()).proceed();
    }

    @Test
    void teamEntityAllowedForMember() throws Throwable {
        when(entityMapper.selectById(9L)).thenReturn(entity(KgEntity.SCOPE_TEAM, 3L, 0L));
        when(scopeService.canSee(any(), any())).thenReturn(true);
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 9L);

        Object result = run(params, 2L);

        assertEquals("TOOL_RESULT", result);
        verify(joinPoint).proceed();
    }

    @Test
    void privateEntityDeniedForNonOwner() throws Throwable {
        when(entityMapper.selectById(9L)).thenReturn(entity(KgEntity.SCOPE_PRIVATE, 0L, 5L));
        when(scopeService.canSee(any(), any())).thenReturn(false);
        Map<String, Object> params = new HashMap<>();
        params.put("entityId", 9L);

        Object result = run(params, 6L);

        assertEquals(GraphPermissionAspect.DENIED, result);
        verify(joinPoint, never()).proceed();
    }

    @Test
    void missingEntityIdSkipsCheck() throws Throwable {
        Object result = run(new HashMap<>(), 1L);
        assertEquals("TOOL_RESULT", result);
        verify(entityMapper, never()).selectById(any(Long.class));
    }

    @Test
    void extractEntityIdSupportsMultipleKeys() {
        assertNull(GraphPermissionAspect.extractEntityId(null));
        assertNull(GraphPermissionAspect.extractEntityId(new HashMap<>()));

        Map<String, Object> snake = new HashMap<>();
        snake.put("entity_id", "11");
        assertEquals(11L, GraphPermissionAspect.extractEntityId(snake));

        Map<String, Object> camel = new HashMap<>();
        camel.put("entityId", 12);
        assertEquals(12L, GraphPermissionAspect.extractEntityId(camel));

        Map<String, Object> plain = new HashMap<>();
        plain.put("id", 13L);
        assertEquals(13L, GraphPermissionAspect.extractEntityId(plain));

        Map<String, Object> bad = new HashMap<>();
        bad.put("entity_id", "abc");
        assertNull(GraphPermissionAspect.extractEntityId(bad));
    }

    @Test
    void deniedPayloadIsStructuredJsonNotException() throws Throwable {
        when(entityMapper.selectById(9L)).thenReturn(entity(KgEntity.SCOPE_TEAM, 3L, 0L));
        when(scopeService.canSee(any(), any())).thenReturn(false);
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 9L);

        Object result = run(params, 8L);

        assertTrue(result instanceof String);
        assertTrue(((String) result).contains("PERMISSION_DENIED"));
        assertTrue(((String) result).startsWith("{"));
    }
}

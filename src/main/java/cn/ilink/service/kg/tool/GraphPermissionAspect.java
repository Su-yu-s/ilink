package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.kg.KgScopeService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GraphTool 权限切面：拦截所有 GraphTool.execute() 调用。
 * 校验入参里的 entityId（兼容 entity_id / entityId / id 三种键名）指向的实体 scope：
 * GLOBAL 放行；TEAM 校验当前用户是否是团队成员；PRIVATE 校验属主。
 * 越权不抛异常，返回结构化错误 JSON，Agent 读到后自动换策略。
 */
@Aspect
@Component
@Slf4j
public class GraphPermissionAspect {

    /** 越权时返回给 Agent 的结构化错误（与工具自身的 errorJson 格式一致） */
    public static final String DENIED = "{\"error\":\"PERMISSION_DENIED\","
        + "\"message\":\"该实体属于团队或个人私有空间，当前用户无权访问。请换一个实体或换一种策略。\"}";

    private final KgEntityMapper entityMapper;
    private final KgScopeService scopeService;

    @Autowired
    public GraphPermissionAspect(KgEntityMapper entityMapper, KgScopeService scopeService) {
        this.entityMapper = entityMapper;
        this.scopeService = scopeService;
    }

    @Around("execution(* cn.ilink.service.kg.tool.GraphTool.execute(..))")
    public Object checkPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        Map<String, Object> params = args.length > 0 && args[0] instanceof Map
            ? (Map<String, Object>) args[0] : null;
        Long userId = args.length > 1 && args[1] instanceof Long ? (Long) args[1] : null;

        Long entityId = extractEntityId(params);
        if (entityId != null) {
            KgEntity entity = entityMapper.selectById(entityId);
            if (entity != null && !scopeService.canSee(entity, userId)) {
                log.info("[KG权限] 拦截越权访问: userId={} entity={} scope={}",
                    userId, entityId, entity.getScope());
                return DENIED;
            }
        }
        return joinPoint.proceed();
    }

    /** 从参数里找实体 ID：entity_id / entityId / id */
    static Long extractEntityId(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        Long id = asLong(params.get("entity_id"));
        if (id == null) {
            id = asLong(params.get("entityId"));
        }
        if (id == null) {
            id = asLong(params.get("id"));
        }
        return id;
    }

    private static Long asLong(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        try {
            String text = String.valueOf(raw).trim();
            return text.isEmpty() ? null : Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

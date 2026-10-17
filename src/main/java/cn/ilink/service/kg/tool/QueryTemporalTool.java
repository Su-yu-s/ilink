package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时序查询（阶段 4 时序知识图谱）：按时间窗过滤仍然有效的关系。
 * Uses half-open intervals: valid_from &lt; endExclusive and (valid_to is null or valid_to &gt; start).
 */
@Component
public class QueryTemporalTool extends AbstractGraphTool {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int SCAN_LIMIT = 500;

    private final KgEntityMapper entityMapper;
    private final KgRelationMapper relationMapper;

    @Autowired
    public QueryTemporalTool(KgScopeService scopeService, KgEntityMapper entityMapper,
                             KgRelationMapper relationMapper) {
        super(scopeService);
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
    }

    @Override
    public String getName() {
        return "query_temporal";
    }

    @Override
    public String getDescription() {
        return "时序查询：查某实体或全局在时间窗内有效的关系（历史状态）。"
            + "触发条件：问题涉及具体年月或时间段（如\"3月\"\"2025年期间\"\"上半年\"）。"
            + "询问特定实体时，先检索确认 entityId；询问所有平台记录时可省略 entity_id，"
            + "但必须提供 time_range。严禁凭空猜测实体 ID。"
            + "返回 coverage.complete=false 时，结果是部分列表，不能据此断言总数或不存在其他关系。";
    }

    @Override
    public String getParametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
            + "\"entity_id\":{\"type\":\"integer\",\"description\":\"目标实体ID\"},"
            + "\"time_range\":{\"type\":\"object\",\"properties\":{"
            + "\"start\":{\"type\":\"string\",\"description\":\"开始日期 yyyy-MM-dd\"},"
            + "\"end\":{\"type\":\"string\",\"description\":\"结束日期 yyyy-MM-dd\"}"
            + "},\"description\":\"时间窗，可省略（省略即全部有效关系）\"},"
            + "\"limit\":{\"type\":\"integer\",\"default\":20,\"description\":\"返回关系数(1-50)\"}"
            + "}}";
    }

    @Override
    public Object execute(Map<String, Object> params, Long userId) {
        Long entityId = paramLong(params, "entity_id");
        int limit = Math.max(1, Math.min(50, paramInt(params, "limit", 20)));
        LocalDate startDay = null;
        LocalDate endDay = null;
        if (params != null && params.get("time_range") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> range = (Map<String, Object>) params.get("time_range");
            try {
                startDay = parseSingle(range.get("start"));
                endDay = parseSingle(range.get("end"));
            } catch (DateTimeException e) {
                return errorJson("INVALID_PARAMS", "time_range 日期必须为 yyyy-MM-dd 且真实存在");
            }
        }
        if (startDay != null && endDay != null && startDay.isAfter(endDay)) {
            return errorJson("INVALID_PARAMS", "time_range.start 不能晚于 end");
        }
        if (entityId != null && entityId <= 0) {
            return errorJson("INVALID_PARAMS", "entity_id 必须是正整数");
        }
        if (entityId == null && startDay == null && endDay == null) {
            return errorJson("INVALID_PARAMS", "全局时序查询必须提供 time_range");
        }
        KgEntity entity = entityId == null ? null : entityMapper.selectById(entityId);
        if (entityId != null && (entity == null
            || !KgEntity.STATUS_ACTIVE.equals(entity.getStatus()))) {
            return errorJson("NOT_FOUND", "实体 #" + entityId + " 不存在");
        }
        if (entity != null && !scopeService.canSee(entity, userId)) {
            return errorJson("PERMISSION_DENIED", "无权访问实体 #" + entityId);
        }
        final Date rangeStart = startDay == null ? null : dayStart(startDay);
        final Date rangeEndExclusive = endDay == null ? null : dayStart(endDay.plusDays(1));
        final Date now = cn.ilink.service.kg.KgTimeRange.now();

        QueryWrapper<KgRelation> wrapper = new QueryWrapper<>();
        if (entityId != null) {
            wrapper.and(w -> w.eq("source_id", entityId).or().eq("target_id", entityId));
        }
        if (startDay == null && endDay == null) {
            wrapper.and(w -> w.isNull("valid_from").or().le("valid_from", now));
            wrapper.and(w -> w.isNull("valid_to").or().gt("valid_to", now));
        } else {
            wrapper.isNotNull("valid_from");
            if (rangeStart != null) {
                wrapper.and(w -> w.isNull("valid_to").or().gt("valid_to", rangeStart));
            }
            if (rangeEndExclusive != null) {
                wrapper.lt("valid_from", rangeEndExclusive);
            }
        }
        wrapper.and(w -> {
            w.eq("scope", KgEntity.SCOPE_GLOBAL);
            if (userId != null && userId > 0) {
                w.or().eq("scope", KgEntity.SCOPE_PRIVATE).eq("user_id", userId);
                List<Long> teams = scopeService.visibleTeamIds(userId);
                if (teams != null && !teams.isEmpty()) {
                    w.or().eq("scope", KgEntity.SCOPE_TEAM).in("team_id", teams);
                }
            }
        });
        // One additional row proves whether the bounded scan exhausted the query. It is not emitted.
        wrapper.orderByAsc("id").last("LIMIT " + (SCAN_LIMIT + 1));
        List<KgRelation> edges = relationMapper.selectList(wrapper);
        boolean scanLimited = edges != null && edges.size() > SCAN_LIMIT;

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        int scanned = 0;
        int candidate = 0;
        int visible = 0;
        Map<Long, KgEntity> endpointCache = new HashMap<>();
        if (entityId != null) {
            endpointCache.put(entityId, entity);
        }
        List<KgRelation> boundedEdges = edges == null ? List.of()
            : edges.subList(0, Math.min(SCAN_LIMIT, edges.size()));
        for (KgRelation edge : boundedEdges) {
            if (edge == null || edge.getId() == null || edge.getSourceId() == null
                || edge.getTargetId() == null || !scopeService.canSee(edge.getScope(),
                edge.getTeamId(), edge.getUserId(), userId)) {
                continue;
            }
            KgEntity source = endpointCache.computeIfAbsent(edge.getSourceId(), entityMapper::selectById);
            KgEntity target = endpointCache.computeIfAbsent(edge.getTargetId(), entityMapper::selectById);
            if (source == null || target == null
                || !KgEntity.STATUS_ACTIVE.equals(source.getStatus())
                || !KgEntity.STATUS_ACTIVE.equals(target.getStatus())
                || !scopeService.canSee(source, userId) || !scopeService.canSee(target, userId)) {
                continue;
            }
            // Counts do not expose edges whose relation or either endpoint is inaccessible.
            scanned++;
            if (!overlaps(edge, rangeStart, rangeEndExclusive, now)) {
                continue;
            }
            candidate++;
            if (!seen.add(edge.getId())) {
                continue;
            }
            visible++;
            if (rows.size() >= limit) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("relationId", edge.getId());
            row.put("sourceId", edge.getSourceId());
            row.put("sourceName", source.getDisplayName());
            row.put("targetId", edge.getTargetId());
            row.put("targetName", target.getDisplayName());
            row.put("relationType", edge.getRelationType());
            row.put("validFrom", formatDate(edge.getValidFrom()));
            row.put("validTo", formatDate(edge.getValidTo()));
            row.put("sourceDocId", edge.getSourceDocId());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityId", entityId);
        result.put("name", entity == null ? "全局时间窗" : entity.getDisplayName());
        result.put("timeRange", (startDay == null ? null : startDay) + " ~ "
            + (endDay == null ? "至今" : endDay));
        result.put("count", rows.size());
        result.put("relations", rows);
        Map<String, Object> queryScope = new LinkedHashMap<>();
        queryScope.put("entityId", entityId);
        queryScope.put("direction", "BOTH");
        queryScope.put("visibility", "CURRENT_USER");
        queryScope.put("match", startDay == null && endDay == null ? "CURRENT" : "OVERLAP_HALF_OPEN");
        queryScope.put("startInclusive", startDay == null ? null : startDay.toString());
        queryScope.put("endExclusive", endDay == null ? null : endDay.plusDays(1).toString());
        queryScope.put("currentAt", now.toInstant().toString());
        result.put("coverage", GraphQueryCoverage.of(queryScope, SCAN_LIMIT, scanLimited,
            scanned, candidate, visible, rows.size(), limit));
        return result;
    }

    private static LocalDate parseSingle(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static Date dayStart(LocalDate day) {
        return Date.from(day.atStartOfDay(ZONE).toInstant());
    }

    private static String formatDate(Date date) {
        return date == null ? null : java.time.Instant.ofEpochMilli(date.getTime())
            .atZone(ZONE).toLocalDate().toString();
    }

    private static boolean overlaps(KgRelation edge, Date start, Date end, Date now) {
        if (start == null && end == null) {
            return (edge.getValidFrom() == null || !edge.getValidFrom().after(now))
                && (edge.getValidTo() == null || edge.getValidTo().after(now));
        }
        return edge.getValidFrom() != null
            && (start == null || edge.getValidTo() == null || edge.getValidTo().after(start))
            && (end == null || edge.getValidFrom().before(end));
    }
}

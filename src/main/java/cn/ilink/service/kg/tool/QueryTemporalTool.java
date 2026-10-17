package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时序查询（阶段 4 时序知识图谱）：按时间窗过滤仍然有效的关系。
 * 时间范围过滤条件：valid_from &lt;= rangeEnd 且（valid_to 为空 或 valid_to &gt;= rangeStart）。
 */
@Component
public class QueryTemporalTool extends AbstractGraphTool {

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
        return "时序查询：查某实体在时间窗内有效的关系（历史状态）。"
            + "触发条件：问题涉及具体年月或时间段（如\"3月\"\"2025年期间\"\"上半年\"）。"
            + "使用前置：必须先用 search_entities 或 filter_entities 检索定位到目标实体的 entityId，"
            + "再传入本工具；严禁凭空猜测或使用未经检索确认的 entityId。";
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
            + "},\"required\":[\"entity_id\"]}";
    }

    @Override
    public Object execute(Map<String, Object> params, Long userId) {
        Long entityId = paramLong(params, "entity_id");
        int limit = Math.max(1, Math.min(50, paramInt(params, "limit", 20)));
        if (entityId == null || entityId <= 0) {
            return errorJson("INVALID_PARAMS", "entity_id 必须是正整数");
        }
        KgEntity entity = entityMapper.selectById(entityId);
        if (entity == null || !KgEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
            return errorJson("NOT_FOUND", "实体 #" + entityId + " 不存在");
        }
        if (!scopeService.canSee(entity, userId)) {
            return errorJson("PERMISSION_DENIED", "无权访问实体 #" + entityId);
        }

        Date rangeStartRaw = null;
        Date rangeEndRaw = null;
        if (params != null && params.get("time_range") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> range = (Map<String, Object>) params.get("time_range");
            rangeStartRaw = parseSingle(range.get("start"), false);
            rangeEndRaw = parseSingle(range.get("end"), true);
        }
        final Date rangeStart = rangeStartRaw;
        final Date rangeEnd = rangeEndRaw;
        if (rangeStart != null && rangeEnd != null && rangeStart.after(rangeEnd)) {
            return errorJson("INVALID_PARAMS", "time_range.start 不能晚于 end");
        }

        QueryWrapper<KgRelation> wrapper = new QueryWrapper<KgRelation>()
            .and(w -> w.eq("source_id", entityId).or().eq("target_id", entityId));
        if (rangeStart == null && rangeEnd == null) {
            // 无时间窗 = 只看当前仍有效的边
            wrapper.isNull("valid_to");
        } else {
            // 窗口交集语义：边生效期 [valid_from, valid_to) 与 [start, end] 有交集
            if (rangeStart != null) {
                wrapper.and(w -> w.isNull("valid_to").or().ge("valid_to", rangeStart));
            }
            if (rangeEnd != null) {
                wrapper.le("valid_from", rangeEnd);
            }
        }
        wrapper.last("LIMIT " + limit);
        List<KgRelation> edges = relationMapper.selectList(wrapper);

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KgRelation edge : edges) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("relationId", edge.getId());
            row.put("sourceId", edge.getSourceId());
            row.put("targetId", edge.getTargetId());
            row.put("relationType", edge.getRelationType());
            row.put("validFrom", edge.getValidFrom() == null ? null : fmt.format(edge.getValidFrom()));
            row.put("validTo", edge.getValidTo() == null ? null : fmt.format(edge.getValidTo()));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityId", entityId);
        result.put("timeRange", (rangeStart == null ? null : fmt.format(rangeStart)) + " ~ "
            + (rangeEnd == null ? "至今" : fmt.format(rangeEnd)));
        result.put("count", rows.size());
        result.put("relations", rows);
        return result;
    }

    private static Date parseSingle(Object raw, boolean endOfDay) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
            Date date = fmt.parse(text);
            if (endOfDay) {
                // 结束日期取当天 23:59:59，含端点
                return new Date(date.getTime() + 24L * 3600_000 - 1000);
            }
            return date;
        } catch (ParseException e) {
            return null;
        }
    }
}

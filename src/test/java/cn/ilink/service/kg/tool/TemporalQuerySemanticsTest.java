package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【时序语义测试 · 测试代码】QueryTemporalTool 的时间窗 SQL 语义断言。
 *
 * <p>方法：捕获工具提交给 Mapper 的 QueryWrapper，检查其 SQL 片段是否与设计一致：
 * - 无时间窗 → 仅取仍有效关系（valid_to IS NULL）；
 * - 带时间窗 → 边生效期 [valid_from, valid_to) 与查询窗口 [start, end] 求交集；
 * - start &gt; end → 参数校验失败，不发起任何查询。
 *
 * <p>这类断言不依赖数据库，因此可在 CI 中稳定运行、可复现。
 */
class TemporalQuerySemanticsTest {

    private KgEntityMapper entityMapper;
    private KgRelationMapper relationMapper;
    private KgScopeService scopeService;
    private QueryTemporalTool tool;

    @BeforeEach
    void setUp() {
        entityMapper = mock(KgEntityMapper.class);
        relationMapper = mock(KgRelationMapper.class);
        scopeService = mock(KgScopeService.class);

        KgEntity entity = new KgEntity();
        entity.setId(3L);
        entity.setName("数学建模竞赛");
        entity.setDisplayName("数学建模竞赛");
        entity.setEntityType("COMPETITION");
        entity.setScope(KgEntity.SCOPE_GLOBAL);
        entity.setStatus(KgEntity.STATUS_ACTIVE);
        when(entityMapper.selectById(3L)).thenReturn(entity);
        when(scopeService.canSee(any(), any())).thenReturn(true);
        when(relationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        tool = new QueryTemporalTool(scopeService, entityMapper, relationMapper);
    }

    @SuppressWarnings("unchecked")
    private String capturedSql() {
        ArgumentCaptor<QueryWrapper<KgRelation>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(relationMapper).selectList(captor.capture());
        return captor.getValue().getSqlSegment().replaceAll("\\s+", " ");
    }

    private Map<String, Object> params(Map<String, Object> timeRange) {
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 3);
        if (timeRange != null) {
            params.put("time_range", timeRange);
        }
        return params;
    }

    @Test
    @DisplayName("无时间窗：按当前时刻过滤有效关系，且端点匹配")
    void noWindow_onlyCurrentlyValid() {
        tool.execute(params(null), 7L);
        String sql = capturedSql();

        assertTrue(sql.contains("source_id"), "应包含端点匹配：" + sql);
        assertTrue(sql.contains("target_id"), "应包含端点匹配：" + sql);
        assertTrue(sql.contains("valid_to IS NULL"), "无窗口应取未设置终止时间的关系：" + sql);
        assertTrue(sql.contains("valid_from"), "无窗口应排除尚未生效的关系：" + sql);
    }

    @Test
    @DisplayName("带完整时间窗：左闭右开区间相交")
    void fullWindow_intersectionSemantics() {
        Map<String, Object> range = new HashMap<>();
        range.put("start", "2026-03-01");
        range.put("end", "2026-03-31");

        tool.execute(params(range), 7L);
        String sql = capturedSql();

        assertTrue(sql.contains("valid_from <"), "应包含 valid_from < endExclusive：" + sql);
        assertTrue(sql.contains("valid_to IS NULL"), "窗口查询也要包含未失效边：" + sql);
        assertTrue(sql.contains("valid_to >"), "应包含 valid_to > start：" + sql);
    }

    @Test
    @DisplayName("只给 start：保留 valid_to > start（无 valid_from 上界条件）")
    void startOnly() {
        Map<String, Object> range = new HashMap<>();
        range.put("start", "2026-03-01");

        tool.execute(params(range), 7L);
        String sql = capturedSql();

        assertTrue(sql.contains("valid_to >"), "应包含 valid_to > start：" + sql);
        assertFalse(sql.contains("valid_from <"), "只给 start 时不应出现 valid_from 上界：" + sql);
    }

    @Test
    @DisplayName("只给 end：补充 valid_from < endExclusive")
    void endOnly() {
        Map<String, Object> range = new HashMap<>();
        range.put("end", "2026-03-31");

        tool.execute(params(range), 7L);
        String sql = capturedSql();

        assertTrue(sql.contains("valid_from <"), "应包含 valid_from < endExclusive：" + sql);
        assertFalse(sql.contains("valid_to >"), "只给 end 时不应出现 valid_to 下界：" + sql);
    }

    @Test
    @DisplayName("start 晚于 end：参数校验失败，不发起任何数据库查询")
    void invertedRange_rejectedWithoutQuery() {
        Map<String, Object> range = new HashMap<>();
        range.put("start", "2026-05-01");
        range.put("end", "2026-01-01");

        Object result = tool.execute(params(range), 7L);

        assertTrue(result.toString().contains("INVALID_PARAMS"), "应返回结构化参数错误：" + result);
        verify(relationMapper, org.mockito.Mockito.never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("越权实体：直接拒绝且不查询关系表")
    void invisibleEntity_rejected() {
        when(scopeService.canSee(any(), any())).thenReturn(false);

        Object result = tool.execute(params(null), 7L);

        assertTrue(result.toString().contains("PERMISSION_DENIED"), "越权应返回结构化拒绝：" + result);
        verify(relationMapper, org.mockito.Mockito.never()).selectList(any(Wrapper.class));
    }
}

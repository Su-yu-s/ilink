package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * KG 维护定时任务（阶段 4 时序图谱）：
 * 每天扫描一次关系，若一条关系的两端实体都已合并/删除，则把该边标记过期（valid_to=当前时间）。
 * 只增不改的简化增量策略下，这是唯一让旧边失效的入口。
 */
@Service
@ConditionalOnProperty(name = "app.kg.maintenance.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class KgMaintenanceService {

    private final KgRelationMapper relationMapper;
    private final KgEntityMapper entityMapper;

    @Autowired
    public KgMaintenanceService(KgRelationMapper relationMapper, KgEntityMapper entityMapper) {
        this.relationMapper = relationMapper;
        this.entityMapper = entityMapper;
    }

    /** 每天凌晨 3:17 扫描（避开已有的 3:23/3:41 任务） */
    @Scheduled(cron = "0 17 3 * * *")
    public void expireOrphanRelations() {
        try {
            int expired = 0;
            Date now = new Date();
            // 分批扫描仍有效的关系（首批 200 条足够毕设规模；数据量增长后可翻页）
            var edges = relationMapper.selectList(new QueryWrapper<KgRelation>()
                .isNull("valid_to").last("LIMIT 200"));
            for (KgRelation edge : edges) {
                if (bothEndsGone(edge.getSourceId(), edge.getTargetId())) {
                    edge.setValidTo(now);
                    relationMapper.updateById(edge);
                    expired++;
                }
            }
            if (expired > 0) {
                log.info("[KG维护] 本轮标记过期关系 {} 条", expired);
            }
        } catch (Exception e) {
            log.warn("[KG维护] 过期关系扫描失败: {}", e.getMessage());
        }
    }

    private boolean bothEndsGone(Long sourceId, Long targetId) {
        return isGone(sourceId) && isGone(targetId);
    }

    private boolean isGone(Long entityId) {
        KgEntity entity = entityMapper.selectById(entityId);
        return entity == null || !KgEntity.STATUS_ACTIVE.equals(entity.getStatus());
    }
}

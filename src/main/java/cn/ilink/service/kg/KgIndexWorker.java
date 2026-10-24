package cn.ilink.service.kg;

import cn.ilink.entity.KgIndexTask;
import cn.ilink.mapper.KgIndexTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * KG 索引 Worker：每 30 秒拉取 PENDING 任务，2 线程并发处理。
 * 用条件更新（PENDING→RUNNING）抢占任务，天然防止多实例/多线程重复消费。
 */
@Service
@ConditionalOnProperty(name = "app.kg.worker.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class KgIndexWorker {

    /** 每轮最多认领的任务数 */
    private static final int BATCH_SIZE = 10;
    private static final long LEASE_SECONDS = 300L;

    private final KgIndexTaskMapper taskMapper;
    private final KgIndexService indexService;
    private final ExecutorService executor;
    private final ScheduledExecutorService leaseRenewer;
    private final String workerId = "kg-" + UUID.randomUUID();

    @Autowired
    public KgIndexWorker(KgIndexTaskMapper taskMapper, KgIndexService indexService) {
        this.taskMapper = taskMapper;
        this.indexService = indexService;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "kg-index-worker");
            t.setDaemon(true);
            return t;
        });
        this.leaseRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kg-index-lease-renewer");
            t.setDaemon(true);
            return t;
        });
    }

    /** 每 30 秒扫一次队列；上轮没跑完时 fixedDelay 保证不重叠提交 */
    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void pollAndDispatch() {
        try {
            Date now = new Date();
            List<KgIndexTask> tasks = taskMapper.selectList(new QueryWrapper<KgIndexTask>()
                .and(w -> w
                    .and(pending -> pending.eq("status", KgIndexTask.PENDING)
                        .and(available -> available.isNull("available_at")
                            .or().le("available_at", now)))
                    .or(expired -> expired.eq("status", KgIndexTask.RUNNING)
                        .and(lease -> lease.isNull("lease_until")
                            .or().lt("lease_until", now))))
                .orderByAsc("id")
                .last("LIMIT " + BATCH_SIZE));
            for (KgIndexTask task : tasks) {
                if (claim(task)) {
                    executor.submit(() -> {
                        ScheduledFuture<?> renewal = null;
                        try {
                            renewal = leaseRenewer.scheduleAtFixedRate(
                                () -> indexService.renewLease(task, workerId,
                                    task.getLeaseToken(), LEASE_SECONDS),
                                60, 60, TimeUnit.SECONDS);
                            indexService.processTask(task, workerId, task.getLeaseToken());
                        } catch (Exception e) {
                            // processTask 内部已兜底，这里防御 Worker 线程本身不因未捕获异常死亡
                            log.error("[KG] Worker 线程处理任务 {} 时出现未预期异常", task.getId(), e);
                        } finally {
                            if (renewal != null) renewal.cancel(false);
                        }
                    });
                }
            }
            if (!tasks.isEmpty()) {
                log.info("[KG] 本轮扫描到 {} 个 PENDING 任务", tasks.size());
            }
        } catch (Exception e) {
            log.warn("[KG] 任务扫描失败: {}", e.getMessage());
        }
    }

    /**
     * Claim a PENDING revision or recover an expired RUNNING lease. Every
     * claim receives a fresh token; the immutable claim tuple is the fence
     * checked by publication and finalization.
     */
    private boolean claim(KgIndexTask task) {
        Date now = new Date();
        Date leaseUntil = new Date(now.getTime() + LEASE_SECONDS * 1000L);
        String token = UUID.randomUUID().toString();
        UpdateWrapper<KgIndexTask> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", task.getId())
            .eq("requested_revision", task.getRequestedRevision() == null ? 0L : task.getRequestedRevision())
            .and(w -> w
                .and(pending -> pending.eq("status", KgIndexTask.PENDING)
                    .and(available -> available.isNull("available_at")
                        .or().le("available_at", now)))
                .or(expired -> expired.eq("status", KgIndexTask.RUNNING)
                    .and(lease -> lease.isNull("lease_until")
                        .or().lt("lease_until", now))))
            .set("status", KgIndexTask.RUNNING)
            .set("available_at", null)
            .set("lease_owner", workerId)
            .set("lease_token", token)
            .set("lease_until", leaseUntil)
            .set("updated_at", now);
        int updated = taskMapper.update(null, wrapper);
        if (updated > 0) {
            task.setStatus(KgIndexTask.RUNNING);
            task.setLeaseOwner(workerId);
            task.setLeaseToken(token);
            task.setLeaseUntil(leaseUntil);
            return true;
        }
        return false;
    }
}

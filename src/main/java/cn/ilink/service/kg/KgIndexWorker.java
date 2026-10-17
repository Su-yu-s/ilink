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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final KgIndexTaskMapper taskMapper;
    private final KgIndexService indexService;
    private final ExecutorService executor;

    @Autowired
    public KgIndexWorker(KgIndexTaskMapper taskMapper, KgIndexService indexService) {
        this.taskMapper = taskMapper;
        this.indexService = indexService;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "kg-index-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** 每 30 秒扫一次队列；上轮没跑完时 fixedDelay 保证不重叠提交 */
    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void pollAndDispatch() {
        try {
            List<KgIndexTask> tasks = taskMapper.selectList(new QueryWrapper<KgIndexTask>()
                .eq("status", KgIndexTask.PENDING)
                .orderByAsc("id")
                .last("LIMIT " + BATCH_SIZE));
            for (KgIndexTask task : tasks) {
                if (claim(task)) {
                    executor.submit(() -> {
                        try {
                            indexService.processTask(task);
                        } catch (Exception e) {
                            // processTask 内部已兜底，这里防御 Worker 线程本身不因未捕获异常死亡
                            log.error("[KG] Worker 线程处理任务 {} 时出现未预期异常", task.getId(), e);
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

    /** 条件更新抢占任务：只有当状态仍是 PENDING 时才置为 RUNNING */
    private boolean claim(KgIndexTask task) {
        UpdateWrapper<KgIndexTask> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", task.getId())
            .eq("status", KgIndexTask.PENDING)
            .set("status", KgIndexTask.RUNNING);
        int updated = taskMapper.update(null, wrapper);
        if (updated > 0) {
            task.setStatus(KgIndexTask.RUNNING);
            return true;
        }
        return false;
    }
}

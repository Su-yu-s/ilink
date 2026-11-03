package cn.ilink.teamagent;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行期暂存：工具直接产生的动作草案（例如 agent 生成的文件）。
 *
 * 这些草案不是模型输出的 JSON，而是服务端在工具调用时构造的，因此先按 runId
 * 暂存，等 run 正常收口时再与模型草案一起交给动作组。进程重启会丢失，
 * 这是可接受的：文件仍在沙箱里，用户可以重新触发。
 */
@Component
public class PendingAgentDrafts {
    private static final long TTL_SECONDS = 1800;

    private record Entry(List<TeamAgentActionDraft> drafts, Instant createdAt) { }

    private final Map<Long, Entry> byRun = new ConcurrentHashMap<>();

    public void add(Long runId, TeamAgentActionDraft draft) {
        if (runId == null || draft == null) return;
        byRun.compute(runId, (key, existing) -> {
            List<TeamAgentActionDraft> rows = existing == null
                ? new ArrayList<>() : new ArrayList<>(existing.drafts());
            rows.add(draft);
            return new Entry(List.copyOf(rows), Instant.now());
        });
    }

    /** 取出并清空该 run 的草案；run 收口只调用一次。 */
    public List<TeamAgentActionDraft> take(Long runId) {
        if (runId == null) return List.of();
        Entry entry = byRun.remove(runId);
        if (entry == null) return List.of();
        if (entry.createdAt().plusSeconds(TTL_SECONDS).isBefore(Instant.now())) return List.of();
        return entry.drafts();
    }

    public void clear(Long runId) {
        if (runId != null) byRun.remove(runId);
    }
}

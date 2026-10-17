package cn.ilink.service.kg;

import cn.ilink.entity.Asset;
import cn.ilink.entity.Competition;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.TeamTask;
import cn.ilink.mapper.AssetMapper;
import cn.ilink.mapper.CompetitionMapper;
import cn.ilink.mapper.CommunityPostMapper;
import cn.ilink.mapper.TeamTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量数据回填：应用启动时把既有文章/竞赛/任务/成果幂等入知识图谱索引队列。
 * enqueue 按 (doc_type, doc_id) 去重，重复启动零成本；历史上只接线了「创建时入队」，
 * 存量数据只能靠这里补齐（服务器部署后首次启动自动回填，无需手工 SQL）。
 */
@Component
@ConditionalOnProperty(name = "app.kg.backfill.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class KgBackfillRunner implements ApplicationRunner {

    private final KgIndexService kgIndexService;
    private final CommunityPostMapper communityPostMapper;
    private final CompetitionMapper competitionMapper;
    private final TeamTaskMapper teamTaskMapper;
    private final AssetMapper assetMapper;

    @Autowired
    public KgBackfillRunner(KgIndexService kgIndexService,
                            CommunityPostMapper communityPostMapper,
                            CompetitionMapper competitionMapper,
                            TeamTaskMapper teamTaskMapper,
                            AssetMapper assetMapper) {
        this.kgIndexService = kgIndexService;
        this.communityPostMapper = communityPostMapper;
        this.competitionMapper = competitionMapper;
        this.teamTaskMapper = teamTaskMapper;
        this.assetMapper = assetMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        int queued = 0;
        for (CommunityPost post : communityPostMapper.selectList(null)) {
            // 文章正文是净化后的 HTML，抽取前剥标签
            if (kgIndexService.enqueue("POST", post.getId(), post.getTitle(),
                    Jsoup.parse(post.getContent() == null ? "" : post.getContent()).text(),
                    KgEntity.SCOPE_GLOBAL, 0L, post.getAuthorId())) {
                queued++;
            }
        }
        for (Competition c : competitionMapper.selectList(null)) {
            String content = "竞赛名称：" + c.getName()
                + (c.getTrack() == null ? "" : "；赛道：" + c.getTrack())
                + (c.getOrganizer() == null ? "" : "；主办方：" + c.getOrganizer())
                + (c.getLevelClass() == null ? "" : "；级别：" + c.getLevelClass())
                + (c.getDescription() == null || c.getDescription().isBlank()
                    ? "" : "；简介：" + c.getDescription());
            if (kgIndexService.enqueue("COMPETITION", c.getId(), c.getName(), content,
                    KgEntity.SCOPE_GLOBAL, 0L, 0L)) {
                queued++;
            }
        }
        for (TeamTask task : teamTaskMapper.selectList(null)) {
            if (kgIndexService.enqueue("TASK", task.getId(), task.getTaskTitle(), task.getTaskDescription(),
                    KgEntity.SCOPE_TEAM, task.getTeamId(), task.getCreatedBy())) {
                queued++;
            }
        }
        for (Asset asset : assetMapper.selectList(null)) {
            if (kgIndexService.enqueue("ASSET", asset.getId(), asset.getTitle(), asset.getDescription(),
                    KgEntity.SCOPE_GLOBAL, 0L, asset.getUserId())) {
                queued++;
            }
        }
        if (queued > 0) {
            log.info("[KG] 存量回填完成，本次新入队 {} 条（其余已在队列，幂等跳过）", queued);
        }
    }
}

package cn.ilink.service.kg;

import cn.ilink.entity.KgCommunity;
import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgCommunityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.KgRelationService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 社区摘要服务：以一个实体为种子取 1 跳邻域，调 Agnes 生成社区标题与摘要，
 * 写入 kg_community（GraphRAG 社区摘要的轻量降级实现：不跑图聚类，按需逐实体生成）。
 */
@Service
@Slf4j
public class KgCommunityService {

    private final KgEntityMapper entityMapper;
    private final KgCommunityMapper communityMapper;
    private final KgRelationService relationService;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public KgCommunityService(KgEntityMapper entityMapper, KgCommunityMapper communityMapper,
                              KgRelationService relationService, AiClient aiClient) {
        this.entityMapper = entityMapper;
        this.communityMapper = communityMapper;
        this.relationService = relationService;
        this.aiClient = aiClient;
    }

    /**
     * 为实体生成（并缓存）社区摘要。
     *
     * @return 摘要记录；邻域过小 / 生成失败返回 null（调用方降级）
     */
    public KgCommunity buildCommunityForEntity(Long entityId, Long userId) {
        try {
            KgRelationService.SubGraph graph = relationService.expandTwoHops(List.of(entityId));
            // 邻域太小不值得建社区；太大按上限截断
            if (graph.entityIds.size() < 3) {
                return null;
            }
            List<KgEntity> members = relationService.getEntitiesByIds(graph.entityIds);
            if (members.size() > 30) {
                members = members.subList(0, 30);
            }
            StringBuilder listing = new StringBuilder();
            for (KgEntity member : members) {
                listing.append("- [").append(member.getEntityType()).append("] ")
                    .append(member.getDisplayName());
                if (member.getDescription() != null && !member.getDescription().isEmpty()) {
                    listing.append("：").append(abbreviate(member.getDescription(), 60));
                }
                listing.append('\n');
            }

            String raw = aiClient.chat(List.of(
                    AiClientMessage.system("你是知识图谱分析专家。根据给出的实体清单，概括这个社区（局部邻域）的主题。"
                        + "严格输出 JSON：{\"title\":\"社区标题，15字以内\",\"summary\":\"80~150字的摘要\"}，"
                        + "不要输出任何解释或代码块标记。"),
                    AiClientMessage.user(listing.toString())), 512).content;

            String json = extractJsonObject(raw);
            if (json == null) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            String title = root.path("title").asText("").trim();
            String summary = root.path("summary").asText("").trim();
            if (title.isEmpty() || summary.isEmpty()) {
                return null;
            }

            // 幂等：同一实体邻域已有当日摘要则复用
            KgCommunity existing = communityMapper.selectOne(new QueryWrapper<KgCommunity>()
                .like("entity_ids_json", "\"" + entityId + "\"")
                .orderByDesc("id").last("LIMIT 1"));
            if (existing != null) {
                return existing;
            }
            KgCommunity community = new KgCommunity();
            community.setTitle(title);
            community.setSummary(summary);
            community.setEntityIdsJson(objectMapper.writeValueAsString(graph.entityIds));
            community.setEntityCount(members.size());
            community.setCreatedAt(new Date());
            community.setUpdatedAt(new Date());
            communityMapper.insert(community);
            log.info("[KG] 已生成实体 #{} 的社区摘要（{} 个成员）", entityId, members.size());
            return community;
        } catch (Exception e) {
            log.warn("[KG] 社区摘要生成失败 entity={}: {}", entityId, e.getMessage());
            return null;
        }
    }

    private static String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start < 0 || end <= start) ? null : raw.substring(start, end + 1);
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 内部小工具：避免把 Map 拼装散落在调用处 */
    private static final class AiClientMessage {
        private static java.util.Map<String, String> system(String content) {
            return message("system", content);
        }

        private static java.util.Map<String, String> user(String content) {
            return message("user", content);
        }

        private static java.util.Map<String, String> message(String role, String content) {
            java.util.Map<String, String> map = new java.util.HashMap<>();
            map.put("role", role);
            map.put("content", content);
            return map;
        }
    }
}

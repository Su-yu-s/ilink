package cn.ilink.service.impl;

import cn.ilink.dto.CompetitionRequest;
import cn.ilink.entity.Competition;
import cn.ilink.mapper.CompetitionMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CompetitionServiceImpl extends ServiceImpl<CompetitionMapper, Competition> {
    public static final Set<String> TRACKS = Set.of("cs", "ee", "innovation", "stem", "robot", "general");
    public static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> LEVELS = Set.of("一类A", "一类B", "二类A", "二类B", "三类");
    private static final Set<String> SCOPES = Set.of("国际赛", "国赛", "国赛/省赛", "省赛", "校赛");

    private final ObjectMapper objectMapper;

    public CompetitionServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Competition createCompetition(CompetitionRequest request) {
        Competition competition = new Competition();
        applyRequest(competition, request, true);
        Date now = new Date();
        competition.setCreatedAt(now);
        competition.setUpdatedAt(now);
        save(competition);
        return competition;
    }

    public Competition updateCompetition(Long id, CompetitionRequest request) {
        Competition competition = getById(id);
        if (competition == null) {
            throw new NoSuchElementException("竞赛不存在");
        }
        applyRequest(competition, request, false);
        competition.setUpdatedAt(new Date());
        updateById(competition);
        return competition;
    }

    public Map<String, Object> toView(Competition competition) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", competition.getId());
        row.put("name", competition.getName());
        row.put("track", competition.getTrack());
        row.put("organizer", competition.getOrganizer());
        row.put("season", competition.getSeason());
        row.put("levelClass", competition.getLevelClass());
        row.put("scope", competition.getScope());
        row.put("tags", parseTags(competition.getTags()));
        row.put("description", competition.getDescription());
        row.put("officialUrl", competition.getOfficialUrl());
        row.put("status", competition.getStatus());
        row.put("registrationDeadline", competition.getRegistrationDeadline());
        row.put("createdAt", competition.getCreatedAt());
        row.put("updatedAt", competition.getUpdatedAt());
        return row;
    }

    private void applyRequest(Competition target, CompetitionRequest request, boolean creating) {
        if (request == null) {
            throw new IllegalArgumentException("竞赛信息不能为空");
        }
        target.setName(requireText(request.getName(), "竞赛名称", 160));
        target.setTrack(requireAllowed(request.getTrack(), TRACKS, "竞赛赛道"));
        target.setOrganizer(requireText(request.getOrganizer(), "主办单位", 160));
        target.setSeason(optionalText(request.getSeason(), 160));
        target.setLevelClass(requireAllowed(request.getLevelClass(), LEVELS, "竞赛类别"));
        target.setScope(requireAllowed(request.getScope(), SCOPES, "竞赛级别"));
        target.setTags(serializeTags(request.getTags()));
        target.setDescription(optionalText(request.getDescription(), 2000));
        target.setOfficialUrl(validateOfficialUrl(request.getOfficialUrl()));
        String status = optionalText(request.getStatus(), 20).toUpperCase(Locale.ROOT);
        if (creating && status.isEmpty()) {
            status = "ACTIVE";
        }
        target.setStatus(requireAllowed(status, STATUSES, "发布状态"));
        target.setRegistrationDeadline(request.getRegistrationDeadline());
    }

    private String serializeTags(List<String> tags) {
        List<String> safeTags = tags == null ? List.of() : tags.stream()
            .map(tag -> optionalText(tag, 30))
            .filter(tag -> !tag.isEmpty())
            .distinct()
            .limit(10)
            .collect(Collectors.toList());
        try {
            return objectMapper.writeValueAsString(safeTags);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("竞赛标签格式错误", e);
        }
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(tags, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private String validateOfficialUrl(String value) {
        String url = optionalText(value, 500);
        if (url.isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                throw new IllegalArgumentException("官网地址仅支持 http 或 https 链接");
            }
            return url;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("官网地址格式不正确");
        }
    }

    private String requireText(String value, String field, int maxLength) {
        String normalized = optionalText(value, maxLength);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return normalized;
    }

    private String requireAllowed(String value, Set<String> allowed, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + "不合法");
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("字段长度不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}

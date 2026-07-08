package cn.ilink.vo;

import lombok.Data;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 社区帖子详情 VO — 替代 CommunityController.toDetail()
 */
@Data
public class CommunityPostDetailVO {
    private Long id;
    private String category;
    private String categoryLabel;
    private String title;
    private String content;
    private String authorDisplay;
    private String authorAvatar;
    private Long authorId;
    private Long viewCount;
    private Long likeCount;
    private Long favoriteCount;
    private boolean liked;
    private boolean favorited;
    private boolean isPinned;
    private Date createdAt;
    private Date updatedAt;
    private List<Map<String, Object>> attachments;
}

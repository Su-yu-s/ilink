package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import lombok.Data;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 社区帖子列表项 VO — 替代 CommunityController.toListItem()
 */
@Data
public class CommunityPostListItemVO {
    private Long id;
    private String category;
    private String categoryLabel;
    private String title;
    private String excerpt;
    private String authorDisplay;
    private String authorAvatar;
    private Long authorId;
    private Long viewCount;
    private Long likeCount;
    private Long favoriteCount;
    private boolean liked;
    private boolean favorited;
    private boolean pinned;
    /** 评论数（列表卡展示用，按 post_id 批量统计） */
    private Long commentCount;
    private Date createdAt;
    private Date updatedAt;
}

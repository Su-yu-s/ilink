package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import lombok.Data;
import java.util.Date;
import java.util.List;

/**
 * 社区评论视图 VO — 替代 CommunityController.toCommentView()
 */
@Data
public class CommunityCommentVO {
    private Long id;
    private Long postId;
    private Long userId;
    private String authorDisplay;
    private String authorAvatar;
    private String content;
    private Date createdAt;
    private boolean canDelete;
    /** 父评论 ID；null 为顶层评论 */
    private Long parentId;
    /** 被回复者昵称，前端显示为「回复 @某某」 */
    private String replyToDisplay;
    /** 顶层评论携带的回复列表（按时间正序）；回复自身该字段为空 */
    private List<CommunityCommentVO> replies;
}

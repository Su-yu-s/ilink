package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import lombok.Data;
import java.util.Date;

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
}

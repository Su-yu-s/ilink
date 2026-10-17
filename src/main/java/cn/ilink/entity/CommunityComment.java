package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("community_comment")
public class CommunityComment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long postId;
    /** 所属顶层评论 ID；为 null 表示顶层评论（回复不再嵌套，仅一层） */
    private Long parentId;
    /** 被回复者用户 ID，仅用于展示「回复 @某某」 */
    private Long replyToUserId;
    private Long userId;
    private String content;
    private Date createdAt;
}

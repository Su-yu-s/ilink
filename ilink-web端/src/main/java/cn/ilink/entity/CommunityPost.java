package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("community_post")
public class CommunityPost {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long authorId;
    /** general / tech / competition / resource */
    private String category;
    private String title;
    private String content;
    /** JSON：[{ "name": "文件名", "url": "/uploads/..." }] */
    private String attachments;
    /** 阅读量 */
    private Integer viewCount;
    /** 点赞数 */
    private Integer likeCount;
    /** 收藏数 */
    private Integer favoriteCount;
    private Date createdAt;
}

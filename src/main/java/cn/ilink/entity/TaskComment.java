package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("task_comments")
public class TaskComment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long parentId;
    private Long userId;
    private String content;
    private String commentType;
    private String mentions;
    private String attachments;
    private Integer likeCount;
    private Integer isDeleted;
    private Date createdAt;
    private Date updatedAt;

    public enum CommentType {
        COMMENT("comment", "评论"),
        REPLY("reply", "回复"),
        UPDATE("update", "更新");

        private final String value;
        private final String description;
        CommentType(String value, String description) {
            this.value = value;
            this.description = description;
        }
        public String getValue() {
            return value;
        }
        public String getDescription() {
            return description;
        }
    }
}

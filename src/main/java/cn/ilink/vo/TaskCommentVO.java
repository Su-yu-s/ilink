package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
public class TaskCommentVO {
    private Long id;
    private Long taskId;
    private Long parentId;
    private Long userId;
    private String username;
    private String realName;
    private String avatar;
    private String content;
    private String commentType;
    private String commentTypeDesc;
    private List<String> mentions;
    private List<String> attachments;
    private Integer likeCount;
    private Date createdAt;
    private Date updatedAt;
    private List<TaskCommentVO> replies;
}

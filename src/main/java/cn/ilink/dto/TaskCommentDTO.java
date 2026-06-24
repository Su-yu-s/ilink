package cn.ilink.dto;

import lombok.Data;

@Data
public class TaskCommentDTO {
    private Long taskId;
    private Long parentId;
    private String content;
}

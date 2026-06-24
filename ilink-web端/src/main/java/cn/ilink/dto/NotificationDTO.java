package cn.ilink.dto;

import lombok.Data;

@Data
public class NotificationDTO {
    private Long userId;
    private String type;
    private String title;
    private String content;
    private Long relatedId;
    private String relatedType;
}

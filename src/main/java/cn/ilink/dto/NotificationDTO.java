package cn.ilink.dto;

import lombok.Data;

/**
 * 创建通知的请求 DTO。
 */
@Data
public class NotificationDTO {
    /** 接收用户 ID */
    private Long userId;

    /** 发送用户 ID（可选，不传则由系统代为写入） */
    private Long senderId;

    /** 通知类型 */
    private String type;

    /** 通知标题 */
    private String title;

    /** 通知内容 */
    private String content;

    /** 关联业务 ID */
    private Long relatedId;

    /** 关联业务类型 */
    private String relatedType;
}

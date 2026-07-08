package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import lombok.Data;
import java.util.Date;

@Data
public class ChatMessageVO {
    private Long id;
    private Long teamId;
    private Long senderId;
    private String senderName;
    private String senderAvatar;
    private String content;
    private String messageType;
    private Date createdAt;
}

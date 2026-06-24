package cn.ilink.dto;

import lombok.Data;

@Data
public class TaskParticipantDTO {
    private Long taskId;
    private Long userId;
    private String role;
}

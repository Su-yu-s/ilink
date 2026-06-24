package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("task_participants")
public class TaskParticipant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long userId;
    private String role;
    private BigDecimal contributionHours;
    private BigDecimal contributionRate;
    private Date joinedAt;
    private Date leftAt;
    private String status;

    public enum ParticipantRole {
        OWNER("owner", "创建者"),
        LEAD("lead", "负责人"),
        MEMBER("member", "成员"),
        REVIEWER("reviewer", "审核者");

        private final String value;
        private final String description;
        ParticipantRole(String value, String description) {
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

    public enum ParticipantStatus {
        ACTIVE("active", "活跃"),
        INACTIVE("inactive", "非活跃"),
        LEFT("left", "已离开");

        private final String value;
        private final String description;
        ParticipantStatus(String value, String description) {
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

package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("team_tasks")
public class TeamTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private String taskTitle;
    private String taskDescription;
    private String taskType;
    private Integer priority;
    private String status;
    private BigDecimal estimatedHours;
    private BigDecimal actualHours;
    private Date deadline;
    private Long assignedTo;
    private Long createdBy;
    private Date createdAt;
    private Date updatedAt;
    private Date completedAt;

    public enum TaskStatus {
        PENDING("待处理"),
        IN_PROGRESS("进行中"),
        REVIEW("审核中"),
        COMPLETED("已完成"),
        CANCELLED("已取消");

        private final String description;
        TaskStatus(String description) {
            this.description = description;
        }
        public String getDescription() {
            return description;
        }
    }

    public enum TaskPriority {
        LOW(1, "低"),
        MEDIUM(2, "中"),
        HIGH(3, "高"),
        URGENT(4, "紧急");

        private final int level;
        private final String description;
        TaskPriority(int level, String description) {
            this.level = level;
            this.description = description;
        }
        public int getLevel() {
            return level;
        }
        public String getDescription() {
            return description;
        }
    }

    public enum TaskType {
        DEVELOPMENT("开发"),
        DESIGN("设计"),
        TESTING("测试"),
        DOCUMENTATION("文档"),
        OTHER("其他");

        private final String description;
        TaskType(String description) {
            this.description = description;
        }
        public String getDescription() {
            return description;
        }
    }
}

package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** KG 索引任务队列 */
@Data
@TableName("kg_index_task")
public class KgIndexTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** POST/COMPETITION/TASK/ASSET 等 */
    private String docType;
    private Long docId;
    private String title;
    /** 待抽取正文（已净化） */
    private String content;
    /** PENDING/RUNNING/DONE/FAILED */
    private String status;
    private Integer retryCount;
    private String errorMsg;
    /** GLOBAL/TEAM/PRIVATE */
    private String scope;
    private Long teamId;
    private Long userId;
    private Date createdAt;
    private Date updatedAt;
    private Date finishedAt;

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
}

package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Agent 推理链日志表 */
@Data
@TableName("kg_agent_trace")
public class KgAgentTrace {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String question;
    /** 推理链 JSON：[{round,thought,action,actionInput,observation}] */
    private String trace;
    private String answer;
    /** 引用实体ID列表的 JSON 数组 */
    private String citations;
    private Integer rounds;
    private Integer toolCalls;
    /** OK/FALLBACK/FAILED */
    private String status;
    private Long elapsedMs;
    private Date createdAt;

    public static final String STATUS_OK = "OK";
    public static final String STATUS_FALLBACK = "FALLBACK";
    public static final String STATUS_FAILED = "FAILED";
}

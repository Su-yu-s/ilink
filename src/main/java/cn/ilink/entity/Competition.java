package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.util.Date;

@Data
@TableName("competition")
public class Competition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String track;
    private String organizer;
    private String season;
    private String levelClass;
    private String scope;
    private String tags;
    private String description;
    /** 核心考察点 JSON：[{"t":"商业落地","d":"…"}]，前端渲染成列表 */
    private String highlights;
    private String officialUrl;
    private String status;
    private LocalDate registrationDeadline;
    private Date createdAt;
    private Date updatedAt;
}

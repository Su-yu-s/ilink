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
    private String officialUrl;
    private String status;
    private LocalDate registrationDeadline;
    private Date createdAt;
    private Date updatedAt;
}

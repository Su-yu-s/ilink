package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("teacher_application")
public class TeacherApplication {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String introduction;
    private String researchDirection;
    private String professionalTitle;
    private String projects;
    private String status;
    private Date createdAt;
}

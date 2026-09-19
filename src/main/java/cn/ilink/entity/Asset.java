package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("asset")
public class Asset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String description;
    private String category;
    private String fileUrl;
    private String originalFileName;
    private String coverUrl;
    private Long userId;
    private Integer viewCount;
    private Integer downloadCount;
    /** 是否置顶（管理后台设置，列表优先展示）0否 1是 */
    private Integer isPinned;
    private Date createdAt;
}

package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 团队资料库文件树节点。parent_id 自引用组树；
 * 删除为软删除（回收站），彻底删除置 is_purged 并抹去 file_url。
 */
@Data
@TableName("team_library_files")
public class TeamLibraryFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long parentId;
    private String name;
    private Boolean isFolder;
    private String fileUrl;
    private String originalFileName;
    private Long fileSize;
    private String fileType;
    private Long uploaderId;
    private Boolean isDeleted;
    private Date deletedAt;
    private Long deletedBy;
    private Boolean isPurged;
    private Date createdAt;
    private Date updatedAt;
}

package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_collab_spreadsheet_version")
public class TeamCollabSpreadsheetVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spreadsheetId;
    private Long teamId;
    private Integer version;
    private String title;
    private String columnsJson;
    private String rowsJson;
    private String contentHash;
    private String saveKind;
    private Long actorUserId;
    private String changeSummary;
    private Date createdAt;
}

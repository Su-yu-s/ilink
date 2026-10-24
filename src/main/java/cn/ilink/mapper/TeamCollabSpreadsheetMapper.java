package cn.ilink.mapper;

import cn.ilink.entity.TeamCollabSpreadsheet;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface TeamCollabSpreadsheetMapper extends BaseMapper<TeamCollabSpreadsheet> {
    @Select("SELECT * FROM team_collab_spreadsheet WHERE id = #{id} FOR UPDATE")
    TeamCollabSpreadsheet selectByIdForUpdate(Long id);
}

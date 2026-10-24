package cn.ilink.mapper;

import cn.ilink.entity.TeamCollabDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface TeamCollabDocumentMapper extends BaseMapper<TeamCollabDocument> {
    @Select("SELECT * FROM team_collab_document WHERE id = #{id} FOR UPDATE")
    TeamCollabDocument selectByIdForUpdate(Long id);
}

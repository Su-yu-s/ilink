package cn.ilink.mapper;

import cn.ilink.entity.TeamAgentActionGroup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeamAgentActionGroupMapper extends BaseMapper<TeamAgentActionGroup> {
    @Select("SELECT * FROM team_agent_action_group WHERE id = #{id} FOR UPDATE")
    TeamAgentActionGroup selectByIdForUpdate(@Param("id") Long id);
}

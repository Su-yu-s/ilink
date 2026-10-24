package cn.ilink.mapper;

import cn.ilink.entity.TeamAgentAction;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeamAgentActionMapper extends BaseMapper<TeamAgentAction> {
    @Select("SELECT * FROM team_agent_action WHERE id = #{id} FOR UPDATE")
    TeamAgentAction selectByIdForUpdate(@Param("id") Long id);
}

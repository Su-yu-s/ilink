package cn.ilink.mapper;

import cn.ilink.entity.TeamAgentConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeamAgentConversationMapper extends BaseMapper<TeamAgentConversation> {
    @Select("SELECT * FROM team_agent_conversation WHERE id = #{id} FOR UPDATE")
    TeamAgentConversation selectByIdForUpdate(@Param("id") Long id);
}

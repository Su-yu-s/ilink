package cn.ilink.mapper;

import cn.ilink.entity.TeamDemand;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TeamDemandMapper extends BaseMapper<TeamDemand> {

    @Select("SELECT * FROM team_demand WHERE id = #{id} FOR UPDATE")
    TeamDemand selectByIdForUpdate(@Param("id") Long id);
}

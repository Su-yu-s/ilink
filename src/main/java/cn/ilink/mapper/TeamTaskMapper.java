package cn.ilink.mapper;

import cn.ilink.entity.TeamTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TeamTaskMapper extends BaseMapper<TeamTask> {
    @Select("SELECT * FROM team_tasks WHERE id = #{id} FOR UPDATE")
    TeamTask selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM team_tasks "
        + "WHERE team_id = #{teamId} "
        + "AND status IN ('PENDING', 'IN_PROGRESS', 'REVIEW') "
        + "ORDER BY id FOR UPDATE")
    List<TeamTask> selectOpenByTeamIdForUpdate(@Param("teamId") Long teamId);
}

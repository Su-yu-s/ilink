package cn.ilink.mapper;

import cn.ilink.entity.TeamApplication;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeamApplicationMapper extends BaseMapper<TeamApplication> {

    @Select("SELECT * FROM team_application WHERE id = #{id} FOR UPDATE")
    TeamApplication selectByIdForUpdate(@Param("id") Long id);
}

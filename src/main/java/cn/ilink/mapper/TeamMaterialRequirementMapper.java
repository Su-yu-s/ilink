package cn.ilink.mapper;

import cn.ilink.entity.TeamMaterialRequirement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface TeamMaterialRequirementMapper extends BaseMapper<TeamMaterialRequirement> {
    @Select("SELECT * FROM team_material_requirement WHERE id = #{id} FOR UPDATE")
    TeamMaterialRequirement selectByIdForUpdate(Long id);
}

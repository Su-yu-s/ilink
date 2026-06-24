package cn.ilink.mapper;

import cn.ilink.entity.UserSkill;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface UserSkillMapper extends BaseMapper<UserSkill> {
    
    @Select("SELECT * FROM user_skill WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<UserSkill> findByUserId(@Param("userId") Long userId);
    
    @Select("SELECT * FROM user_skill WHERE id = #{id}")
    UserSkill findById(@Param("id") Long id);
    
    @Delete("DELETE FROM user_skill WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
    
    @Select("SELECT COUNT(*) FROM user_skill WHERE user_id = #{userId} AND skill_name = #{skillName}")
    int countByUserIdAndSkillName(@Param("userId") Long userId, @Param("skillName") String skillName);
}

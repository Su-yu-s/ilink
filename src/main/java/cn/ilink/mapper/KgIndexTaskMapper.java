package cn.ilink.mapper;

import cn.ilink.entity.KgIndexTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KgIndexTaskMapper extends BaseMapper<KgIndexTask> {
    @Select("SELECT * FROM kg_index_task WHERE id = #{id} FOR UPDATE")
    KgIndexTask selectByIdForUpdate(@Param("id") Long id);
}

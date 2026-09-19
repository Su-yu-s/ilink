package cn.ilink.mapper;

import cn.ilink.entity.Asset;
import cn.ilink.vo.AssetCategoryStatVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AssetMapper extends BaseMapper<Asset> {
    @Select("SELECT COALESCE(NULLIF(TRIM(category), ''), '其他') AS category, COUNT(*) AS count " +
        "FROM asset GROUP BY COALESCE(NULLIF(TRIM(category), ''), '其他')")
    List<AssetCategoryStatVO> selectCategoryStats();

    /**
     * 显式清空附件两列。
     * MyBatis-Plus 的 updateById 会跳过值为 null 的字段，直接 setFileUrl(null) 后更新
     * 不会写库——用户看到的仍是「删了但又回来了」，所以这里用一条显式 UPDATE。
     */
    @Update("UPDATE asset SET file_url = NULL, original_file_name = NULL WHERE id = #{id}")
    int clearFileColumns(@Param("id") Long id);

    @Update("UPDATE asset SET cover_url = NULL WHERE id = #{id}")
    int clearCoverColumn(@Param("id") Long id);
}

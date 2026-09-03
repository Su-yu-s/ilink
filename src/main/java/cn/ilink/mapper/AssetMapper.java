package cn.ilink.mapper;

import cn.ilink.entity.Asset;
import cn.ilink.vo.AssetCategoryStatVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AssetMapper extends BaseMapper<Asset> {
    @Select("SELECT COALESCE(NULLIF(TRIM(category), ''), '其他') AS category, COUNT(*) AS count " +
        "FROM asset GROUP BY COALESCE(NULLIF(TRIM(category), ''), '其他')")
    List<AssetCategoryStatVO> selectCategoryStats();
}

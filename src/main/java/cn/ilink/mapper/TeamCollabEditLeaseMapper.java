package cn.ilink.mapper;

import cn.ilink.entity.TeamCollabEditLease;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface TeamCollabEditLeaseMapper extends BaseMapper<TeamCollabEditLease> {
    @Select("SELECT * FROM team_collab_edit_lease "
        + "WHERE asset_type = #{assetType} AND asset_id = #{assetId} FOR UPDATE")
    TeamCollabEditLease selectByAssetForUpdate(String assetType, Long assetId);
}

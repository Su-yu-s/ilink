package cn.ilink.service.impl;

import cn.ilink.entity.Asset;
import cn.ilink.mapper.AssetMapper;
import cn.ilink.vo.AssetCategoryStatVO;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class AssetServiceImpl extends ServiceImpl<AssetMapper, Asset> {

    public java.util.List<AssetCategoryStatVO> getCategoryStats() {
        return baseMapper.selectCategoryStats();
    }

    /** 清空成果附件（含原始文件名）；null 字段无法通过 updateById 写入，故走显式 UPDATE */
    public boolean clearFileColumns(Long assetId) {
        return baseMapper.clearFileColumns(assetId) > 0;
    }

    /** 清空成果封面；原因同上 */
    public boolean clearCoverColumn(Long assetId) {
        return baseMapper.clearCoverColumn(assetId) > 0;
    }
}

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
}

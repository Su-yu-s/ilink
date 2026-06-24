package cn.ilink.service.impl;

import cn.ilink.entity.CommunityPost;
import cn.ilink.mapper.CommunityPostMapper;
import cn.ilink.service.CommunityPostService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class CommunityPostServiceImpl extends ServiceImpl<CommunityPostMapper, CommunityPost> implements CommunityPostService {
}

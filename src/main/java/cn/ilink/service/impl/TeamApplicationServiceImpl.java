package cn.ilink.service.impl;

import cn.ilink.entity.TeamApplication;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.service.TeamApplicationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TeamApplicationServiceImpl extends ServiceImpl<TeamApplicationMapper, TeamApplication> implements TeamApplicationService {
}
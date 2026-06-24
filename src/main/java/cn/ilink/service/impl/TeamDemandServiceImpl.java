package cn.ilink.service.impl;

import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.service.TeamDemandService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TeamDemandServiceImpl extends ServiceImpl<TeamDemandMapper, TeamDemand> implements TeamDemandService {
}
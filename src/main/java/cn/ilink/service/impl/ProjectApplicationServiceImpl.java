package cn.ilink.service.impl;

import cn.ilink.entity.ProjectApplication;
import cn.ilink.mapper.ProjectApplicationMapper;
import cn.ilink.service.ProjectApplicationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ProjectApplicationServiceImpl extends ServiceImpl<ProjectApplicationMapper, ProjectApplication> implements ProjectApplicationService {
}
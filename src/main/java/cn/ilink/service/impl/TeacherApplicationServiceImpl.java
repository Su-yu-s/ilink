package cn.ilink.service.impl;

import cn.ilink.entity.TeacherApplication;
import cn.ilink.mapper.TeacherApplicationMapper;
import cn.ilink.service.TeacherApplicationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TeacherApplicationServiceImpl extends ServiceImpl<TeacherApplicationMapper, TeacherApplication> implements TeacherApplicationService {
}
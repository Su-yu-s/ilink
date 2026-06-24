package cn.ilink.service.impl;

import cn.ilink.entity.Message;
import cn.ilink.mapper.MessageMapper;
import cn.ilink.service.MessageService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {
}
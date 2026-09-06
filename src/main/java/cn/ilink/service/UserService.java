package cn.ilink.service;

import cn.ilink.entity.User;
import cn.ilink.dto.LoginRequest;
import cn.ilink.dto.RegisterRequest;
import cn.ilink.dto.ProfileRequest;
import com.baomidou.mybatisplus.extension.service.IService;

public interface UserService extends IService<User> {
    boolean register(RegisterRequest registerRequest);
    User login(LoginRequest loginRequest);
    /** 登录时为从未分配头像的用户补一个随机内置头像（只补一次，已有头像不动） */
    User ensureAvatarAssigned(User user);
    boolean updateProfile(Long userId, ProfileRequest profileRequest);
    boolean changePassword(Long userId, String oldPassword, String newPassword);
}
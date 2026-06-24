package cn.ilink.service;

import cn.ilink.entity.User;
import cn.ilink.dto.LoginRequest;
import cn.ilink.dto.RegisterRequest;
import cn.ilink.dto.ProfileRequest;
import com.baomidou.mybatisplus.extension.service.IService;

public interface UserService extends IService<User> {
    boolean register(RegisterRequest registerRequest);
    User login(LoginRequest loginRequest);
    boolean updateProfile(Long userId, ProfileRequest profileRequest);
    boolean changePassword(Long userId, String oldPassword, String newPassword);
}
package cn.ilink.service.impl;

import cn.ilink.entity.User;
import cn.ilink.mapper.UserMapper;
import cn.ilink.dto.LoginRequest;
import cn.ilink.dto.RegisterRequest;
import cn.ilink.dto.ProfileRequest;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private boolean matchesPassword(User user, String rawPassword) {
        if (user == null || rawPassword == null) {
            return false;
        }
        String stored = user.getPassword();
        if (stored == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, stored);
    }

    @Override
    public boolean register(RegisterRequest registerRequest) {
        if (registerRequest.getPhoneNumber() != null && !registerRequest.getPhoneNumber().trim().isEmpty()) {
            User existingUserByPhone = userMapper.findByPhoneNumber(registerRequest.getPhoneNumber());
            if (existingUserByPhone != null) {
                log.debug("注册失败：手机号已存在");
                return false;
            }
        }

        if (registerRequest.getStudentId() != null) {
            User existingUserByStudentId = userMapper.findByStudentId(registerRequest.getStudentId().toString());
            if (existingUserByStudentId != null) {
                log.debug("注册失败：学号/工号已存在");
                return false;
            }
        }

        String username = registerRequest.getUsername();
        if (username == null || username.isBlank()) {
            if (registerRequest.getPhoneNumber() != null && !registerRequest.getPhoneNumber().trim().isEmpty()) {
                username = registerRequest.getPhoneNumber().trim();
            } else if (registerRequest.getStudentId() != null) {
                username = "s" + registerRequest.getStudentId();
            } else {
                return false;
            }
        }

        User existingUser = userMapper.findByUsername(username);
        if (existingUser != null) {
            log.debug("注册失败：用户名已存在");
            return false;
        }

        User user = new User();
        user.setUsername(username);
        user.setPhoneNumber(registerRequest.getPhoneNumber());
        user.setStudentId(registerRequest.getStudentId());
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setEmail(registerRequest.getEmail());
        user.setRole(registerRequest.getRole() != null ? registerRequest.getRole() : "STUDENT");
        user.setRealName(username);
        user.setGender(registerRequest.getGender());

        return save(user);
    }

    @Override
    public User login(LoginRequest loginRequest) {
        User user = null;

        if (loginRequest.getPhoneNumber() != null && !loginRequest.getPhoneNumber().isEmpty()) {
            user = userMapper.findByPhoneNumber(loginRequest.getPhoneNumber());
        }

        if (user == null && loginRequest.getStudentId() != null && !loginRequest.getStudentId().isEmpty()) {
            user = userMapper.findByStudentId(loginRequest.getStudentId());
        }

        if (user == null && loginRequest.getIdentifier() != null && !loginRequest.getIdentifier().isEmpty()) {
            String id = loginRequest.getIdentifier().trim();
            if (id.contains("@")) {
                user = userMapper.findByEmail(id);
            }
            if (user == null) {
                user = userMapper.findByUsername(id);
            }
        }

        if (user != null && matchesPassword(user, loginRequest.getPassword())) {
            return user;
        }
        return null;
    }

    @Override
    public boolean updateProfile(Long userId, ProfileRequest profileRequest) {
        User user = getById(userId);
        if (user == null) {
            return false;
        }

        if (profileRequest.getUsername() != null) {
            String nextUsername = profileRequest.getUsername().trim();
            if (nextUsername.isEmpty()) {
                return false;
            }
            User exists = userMapper.findByUsername(nextUsername);
            if (exists != null && exists.getId() != null && !exists.getId().equals(userId)) {
                return false;
            }
            user.setUsername(nextUsername);
        }

        if (profileRequest.getEmail() != null) {
            user.setEmail(profileRequest.getEmail());
        }
        if (profileRequest.getRealName() != null) {
            user.setRealName(profileRequest.getRealName());
        }
        if (profileRequest.getAvatar() != null) {
            user.setAvatar(profileRequest.getAvatar());
        }
        if (profileRequest.getGender() != null) {
            String g = profileRequest.getGender().trim();
            user.setGender(g.isEmpty() ? null : g);
        }
        if (profileRequest.getGrade() != null) {
            String v = profileRequest.getGrade().trim();
            user.setGrade(v.isEmpty() ? null : v);
        }
        if (profileRequest.getMajor() != null) {
            String v = profileRequest.getMajor().trim();
            user.setMajor(v.isEmpty() ? null : v);
        }
        if (profileRequest.getSchool() != null) {
            String v = profileRequest.getSchool().trim();
            user.setSchool(v.isEmpty() ? null : v);
        }
        if (profileRequest.getCollege() != null) {
            String v = profileRequest.getCollege().trim();
            user.setCollege(v.isEmpty() ? null : v);
        }
        if (profileRequest.getBio() != null) {
            String v = profileRequest.getBio().trim();
            user.setBio(v.isEmpty() ? null : v);
        }
        if (profileRequest.getHonors() != null) {
            user.setHonors(profileRequest.getHonors());
        }

        return updateById(user);
    }

    @Override
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        User user = getById(userId);
        if (user == null) return false;
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) return false;
        user.setPassword(passwordEncoder.encode(newPassword));
        updateById(user);
        return true;
    }
}

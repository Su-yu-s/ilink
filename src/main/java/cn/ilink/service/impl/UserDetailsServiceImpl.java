package cn.ilink.service.impl;

import cn.ilink.entity.User;
import cn.ilink.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    
    @Autowired
    private UserMapper userMapper;
    
    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = null;
        
        if (identifier.matches("^1[3-9]\\d{9}$")) {
            user = userMapper.findByPhoneNumber(identifier);
        } else if (identifier.matches("^\\d+$")) {
            user = userMapper.findByStudentId(identifier);
        }
        
        if (user == null) {
            user = userMapper.findByUsername(identifier);
        }
        
        if (user == null) {
            throw new UsernameNotFoundException("User not found with identifier: " + identifier);
        }
        
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
        
        switch (user.getRole()) {
            case "ADMIN":
                authorities.add(new SimpleGrantedAuthority("ACCESS_ADMIN_PANEL"));
                authorities.add(new SimpleGrantedAuthority("MANAGE_USERS"));
                break;
            case "TEACHER":
                authorities.add(new SimpleGrantedAuthority("ACCESS_TEACHER_FEATURES"));
                break;
            case "STUDENT":
                authorities.add(new SimpleGrantedAuthority("ACCESS_STUDENT_FEATURES"));
                break;
        }
        
        return new org.springframework.security.core.userdetails.User(
            user.getUsername(),
            user.getPassword(),
            authorities
        );
    }
}

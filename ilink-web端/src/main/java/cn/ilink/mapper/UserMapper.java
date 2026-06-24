package cn.ilink.mapper;

import cn.ilink.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT * FROM user WHERE username = #{username}")
    User findByUsername(@Param("username") String username);
    
    @Select("SELECT * FROM user WHERE phone_number = #{phoneNumber}")
    User findByPhoneNumber(@Param("phoneNumber") String phoneNumber);
    
    @Select("SELECT * FROM user WHERE student_id = #{studentId}")
    User findByStudentId(@Param("studentId") String studentId);

    @Select("SELECT * FROM user WHERE email = #{email}")
    User findByEmail(@Param("email") String email);
}
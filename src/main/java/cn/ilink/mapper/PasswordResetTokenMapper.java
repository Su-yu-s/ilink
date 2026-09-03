package cn.ilink.mapper;

import cn.ilink.entity.PasswordResetToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PasswordResetTokenMapper extends BaseMapper<PasswordResetToken> {
    @Select("SELECT * FROM password_reset_token WHERE token_hash = #{tokenHash} LIMIT 1 FOR UPDATE")
    PasswordResetToken selectByHashForUpdate(@Param("tokenHash") String tokenHash);
}

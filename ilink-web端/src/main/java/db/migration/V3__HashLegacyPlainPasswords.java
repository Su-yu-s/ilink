package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 将仍为明文的 user.password 批量升级为 BCrypt（Flyway 仅执行一次）。
 */
public class V3__HashLegacyPlainPasswords extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        try (Statement select = context.getConnection().createStatement();
             ResultSet rs = select.executeQuery(
                 "SELECT id, password FROM user WHERE password IS NOT NULL AND password NOT LIKE '$2%'")) {
            String updateSql = "UPDATE user SET password = ? WHERE id = ?";
            try (PreparedStatement update = context.getConnection().prepareStatement(updateSql)) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String plain = rs.getString("password");
                    if (plain == null || plain.isBlank()) {
                        continue;
                    }
                    update.setString(1, encoder.encode(plain));
                    update.setLong(2, id);
                    update.executeUpdate();
                }
            }
        }
    }
}

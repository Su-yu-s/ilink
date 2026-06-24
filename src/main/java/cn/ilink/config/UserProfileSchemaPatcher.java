package cn.ilink.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 个人资料扩展字段自动补齐：
 * grade / major / school / college / bio
 */
@Component
@Order(6)
public class UserProfileSchemaPatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserProfileSchemaPatcher.class);

    @Autowired
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            addColumnIfMissing(conn, st, "grade", "VARCHAR(32) NULL COMMENT '年级' AFTER `gender`");
            addColumnIfMissing(conn, st, "major", "VARCHAR(64) NULL COMMENT '专业' AFTER `grade`");
            addColumnIfMissing(conn, st, "school", "VARCHAR(128) NULL COMMENT '学校' AFTER `major`");
            addColumnIfMissing(conn, st, "college", "VARCHAR(128) NULL COMMENT '学院' AFTER `school`");
            addColumnIfMissing(conn, st, "bio", "VARCHAR(300) NULL COMMENT '个人简介' AFTER `college`");
        } catch (Exception e) {
            log.warn("无法自动补齐 user 扩展字段（grade/major/school/college/bio）：{}", e.getMessage());
        }
    }

    private void addColumnIfMissing(Connection conn, Statement st, String column, String ddlTail) throws Exception {
        if (columnExists(conn, "user", column)) {
            return;
        }
        st.executeUpdate("ALTER TABLE `user` ADD COLUMN `" + column + "` " + ddlTail);
        log.info("已自动为 user 表添加 {} 列", column);
    }

    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        DatabaseMetaData md = conn.getMetaData();
        String catalog = conn.getCatalog();
        if (columnsHasNext(md, catalog, table, column)) {
            return true;
        }
        return columnsHasNext(md, null, table, column);
    }

    private static boolean columnsHasNext(DatabaseMetaData md, String catalog, String table, String column)
        throws Exception {
        try (ResultSet rs = md.getColumns(catalog, null, table, column)) {
            return rs.next();
        }
    }
}

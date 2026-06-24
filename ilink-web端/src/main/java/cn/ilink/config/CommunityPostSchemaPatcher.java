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
 * 旧库若未执行 sql/community_post_attachments.sql，启动时自动补充 {@code community_post.attachments}，
 * 避免出现 Unknown column 'attachments'。
 */
@Component
@Order(5)
public class CommunityPostSchemaPatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommunityPostSchemaPatcher.class);

    @Autowired
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            if (columnExists(conn, "community_post", "attachments")) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(
                    "ALTER TABLE `community_post` ADD COLUMN `attachments` TEXT NULL "
                        + "COMMENT 'JSON list: name + url under /uploads/' AFTER `content`"
                );
                log.info("已自动为 community_post 表添加 attachments 列");
            }
        } catch (Exception e) {
            log.warn(
                "无法自动添加 community_post.attachments 列（可手动执行 sql/community_post_attachments.sql）：{}",
                e.getMessage()
            );
        }
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

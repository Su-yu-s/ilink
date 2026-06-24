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
 * 点赞/收藏表及 community_post 计数列；未执行 sql/community_post_likes_favorites.sql 时自动补齐。
 */
@Component
@Order(6)
public class CommunityPostInteractionSchemaPatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommunityPostInteractionSchemaPatcher.class);

    @Autowired
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                if (!columnExists(conn, "community_post", "like_count")) {
                    st.executeUpdate(
                        "ALTER TABLE `community_post` ADD COLUMN `like_count` INT NOT NULL DEFAULT 0 "
                            + "COMMENT '点赞数' AFTER `view_count`"
                    );
                    log.info("已添加 community_post.like_count");
                }
                if (!columnExists(conn, "community_post", "favorite_count")) {
                    st.executeUpdate(
                        "ALTER TABLE `community_post` ADD COLUMN `favorite_count` INT NOT NULL DEFAULT 0 "
                            + "COMMENT '收藏数' AFTER `like_count`"
                    );
                    log.info("已添加 community_post.favorite_count");
                }

                // 直接使用 IF NOT EXISTS，避免 MySQL 元数据查询在部分环境下不准确导致漏建表
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `community_post_like` ("
                        + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                        + "`post_id` INT NOT NULL,"
                        + "`user_id` BIGINT NOT NULL,"
                        + "`created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (`id`),"
                        + "UNIQUE KEY `uk_post_user_like` (`post_id`,`user_id`),"
                        + "KEY `idx_cpl_user` (`user_id`),"
                        + "CONSTRAINT `fk_cpl_post` FOREIGN KEY (`post_id`) REFERENCES `community_post` (`id`) ON DELETE CASCADE,"
                        + "CONSTRAINT `fk_cpl_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );

                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `community_post_favorite` ("
                        + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                        + "`post_id` INT NOT NULL,"
                        + "`user_id` BIGINT NOT NULL,"
                        + "`created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (`id`),"
                        + "UNIQUE KEY `uk_post_user_fav` (`post_id`,`user_id`),"
                        + "KEY `idx_cpf_user` (`user_id`),"
                        + "CONSTRAINT `fk_cpf_post` FOREIGN KEY (`post_id`) REFERENCES `community_post` (`id`) ON DELETE CASCADE,"
                        + "CONSTRAINT `fk_cpf_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );
            }
        } catch (Exception e) {
            log.warn(
                "无法自动创建点赞/收藏结构（可手动执行 sql/community_post_likes_favorites.sql）：{}",
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

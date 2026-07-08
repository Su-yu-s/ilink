package cn.ilink.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * Flyway 修复配置（仅 dev 环境）。
 * 在标准 migrate 执行之前先调用 repair()，
 * 清理 flyway_schema_history 中残留的失败迁移记录，避免启动卡死。
 */
@Configuration
@Profile("dev")
public class FlywayRepairConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairConfig.class);

    /**
     * 用一个高优先级的 FlywayMigrationInitializer 覆盖默认行为：
     * 先 repair 再 migrate，确保本地开发环境即使迁移失败也能自愈。
     */
    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource,
                         @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
                         @Value("${spring.flyway.baseline-on-migrate:true}") boolean baselineOnMigrate,
                         @Value("${spring.flyway.baseline-version:0}") String baselineVersion) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(baselineVersion)
                .load();
        try {
            log.info("[Flyway] 先执行 repair 清理可能的失败记录");
            flyway.repair();
        } catch (Exception e) {
            // MySQL 5.7 可能没有 performance_schema 表，这是预期行为，静默忽略
            log.debug("[Flyway] repair 跳过（performance_schema 不可用）: {}", e.getMessage());
        }
        return flyway;
    }

    /**
     * 覆盖默认的 FlywayMigrationInitializer，复用上面的 Flyway Bean。
     */
    @Bean
    public FlywayMigrationInitializer flywayInitializer(Flyway flyway) {
        return new FlywayMigrationInitializer(flyway, null);
    }
}

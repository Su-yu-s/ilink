package cn.ilink.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile("prod")
public class ProductionSafetyValidator implements ApplicationRunner {
    private final Environment environment;

    public ProductionSafetyValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = new ArrayList<>();
        String password = property("spring.datasource.password");
        String username = property("spring.datasource.username");
        String publicBaseUrl = property("app.public-base-url");
        String mailHost = property("spring.mail.host");
        String mailFrom = property("app.mail.from");
        String uploadDir = property("file.upload-dir");

        if (password.isBlank() || password.contains("changeme")) {
            problems.add("必须通过 DB_PASSWORD 配置非默认数据库密码");
        }
        if (username.isBlank() || "root".equalsIgnoreCase(username)) {
            problems.add("生产数据库必须使用非 root 的最小权限账号");
        }
        if (!publicBaseUrl.startsWith("https://")) {
            problems.add("APP_PUBLIC_BASE_URL 必须是 https 地址");
        }
        if (mailHost.isBlank()) {
            problems.add("必须配置 SPRING_MAIL_HOST 以发送密码重置邮件");
        }
        if (!mailFrom.contains("@")) {
            problems.add("APP_MAIL_FROM 必须是有效发件地址");
        }
        if (uploadDir.isBlank() || !Path.of(uploadDir).isAbsolute()) {
            problems.add("FILE_UPLOAD_DIR 必须是绝对路径");
        }
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            problems.add("生产会话 Cookie 必须启用 Secure");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产环境安全配置未完成：\n- " + String.join("\n- ", problems));
        }
    }

    private String property(String key) {
        return environment.getProperty(key, "").trim();
    }
}

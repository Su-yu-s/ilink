package cn.ilink.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${file.upload-dir:${upload.path:/data/uploads/}}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String path = StringUtils.hasText(uploadDir) ? uploadDir.trim() : "/data/uploads/";
        Set<String> locations = new LinkedHashSet<>();
        locations.add(toFileLocation(path));
        locations.add(toFileLocation("./data/uploads"));
        locations.add(toFileLocation("/www/wwwroot/ilink/data/uploads"));
        locations.add(toFileLocation("/data/uploads"));

        registry.addResourceHandler("/uploads/**")
            .addResourceLocations(locations.toArray(new String[0]));

        // 静态资源长期缓存。CSS/JS 等资源通过 ?v=xxx 控制版本，
        // 文件内容变化时同步更新版本号，避免跨页面跳转时重复下载导致闪屏。
        registry.addResourceHandler("/css/**", "/js/**", "/lib/**", "/img/**")
            .addResourceLocations("classpath:/static/css/",
                                  "classpath:/static/js/",
                                  "classpath:/static/lib/",
                                  "classpath:/static/img/")
            .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic());
    }

    private String toFileLocation(String path) {
        Path root = Paths.get(path).toAbsolutePath().normalize();
        String location = root.toUri().toString();
        return location.endsWith("/") ? location : location + "/";
    }
}

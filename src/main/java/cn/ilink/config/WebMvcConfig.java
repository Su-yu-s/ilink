package cn.ilink.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final Environment environment;

    @Value("${file.upload-dir:${upload.path:/data/uploads/}}")
    private String uploadDir;

    public WebMvcConfig(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String path = StringUtils.hasText(uploadDir) ? uploadDir.trim() : "/data/uploads/";
        Set<String> locations = new LinkedHashSet<>();
        locations.add(toFileLocation(path));
        locations.add(toFileLocation("./data/uploads"));
        locations.add(toFileLocation("/www/wwwroot/ilink/data/uploads"));
        locations.add(toFileLocation("/data/uploads"));

        registry.addResourceHandler("/uploads/**")
            .addResourceLocations(locations.toArray(new String[0]))
            .setCacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic());

        // 静态资源长期缓存 + 内容指纹版本化：模板里 @{/css/x.css} 渲染为 /css/x-<内容hash>.css，
        // 文件一变 URL 自动变，无需再手动维护 ?v= 版本号。
        // dev 不开内容缓存，保证改文件刷新即生效；prod 缓存内容省去每次算 hash。
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        registry.addResourceHandler("/css/**", "/js/**", "/lib/**", "/img/**")
            .addResourceLocations("classpath:/static/css/",
                                  "classpath:/static/js/",
                                  "classpath:/static/lib/",
                                  "classpath:/static/img/")
            .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
            .resourceChain(prod)
            .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
    }

    private String toFileLocation(String path) {
        Path root = Paths.get(path).toAbsolutePath().normalize();
        String location = root.toUri().toString();
        return location.endsWith("/") ? location : location + "/";
    }
}

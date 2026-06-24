package cn.ilink.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

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
    }

    private String toFileLocation(String path) {
        Path root = Paths.get(path).toAbsolutePath().normalize();
        String location = root.toUri().toString();
        return location.endsWith("/") ? location : location + "/";
    }
}

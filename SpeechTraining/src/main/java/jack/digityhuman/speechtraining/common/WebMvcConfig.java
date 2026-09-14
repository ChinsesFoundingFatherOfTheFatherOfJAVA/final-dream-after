package jack.digityhuman.speechtraining.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 静态资源配置：将上传的封面图片目录映射为可访问的URL路径
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.upload.cover-image-dir:/coverImage}")
    private String coverImageDir;

    @Value("${app.upload.cover-image-url-prefix:/coverImage}")
    private String coverImageUrlPrefix;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String prefix = coverImageUrlPrefix.endsWith("/")
                ? coverImageUrlPrefix.substring(0, coverImageUrlPrefix.length() - 1)
                : coverImageUrlPrefix;
        String location = Paths.get(coverImageDir).toAbsolutePath().normalize().toUri().toString();
        // 目录形式的资源位置必须以 '/' 结尾，否则 Spring 无法正确解析
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(prefix + "/**")
                .addResourceLocations(location);
    }
}

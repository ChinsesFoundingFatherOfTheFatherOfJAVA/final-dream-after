package jack.digityhuman.examservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 静态资源映射：试题媒体文件（exam.files.root 本地磁盘）→ /api/exam/files/**。
 *
 * <p>网关侧 /api/exam/** 路由到本服务后路径带 /api 前缀，故注册两套前缀
 * （同 OnlineClassroom 的 kng-files 模式）。
 */
@Configuration
public class ExamWebConfig implements WebMvcConfigurer {

    @Value("${exam.files.root}")
    private String filesRoot;

    @Value("${exam.files.url-prefix:/exam/files}")
    private String urlPrefix;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String prefix = urlPrefix.endsWith("/") ? urlPrefix.substring(0, urlPrefix.length() - 1) : urlPrefix;
        String location = Paths.get(filesRoot).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(prefix + "/**").addResourceLocations(location);
        registry.addResourceHandler("/api" + prefix + "/**").addResourceLocations(location);
    }
}

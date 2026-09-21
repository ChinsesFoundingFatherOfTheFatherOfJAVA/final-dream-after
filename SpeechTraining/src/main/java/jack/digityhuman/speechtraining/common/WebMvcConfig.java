package jack.digityhuman.speechtraining.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 静态资源配置：将上传文件的本地目录映射为可访问的URL路径
 * - 封面图片：/coverImage/**（v1.0）
 * - 音频（TTS 合成/学员录音）：/audio/**（v1.1）
 * - 陪练者形象图片：/avatar/**（v1.1）
 * 同时注册经网关（/api 前缀）访问的映射，与 @RequestMapping("/api/...") 的约定保持一致
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.upload.cover-image-dir:/coverImage}")
    private String coverImageDir;

    @Value("${app.upload.cover-image-url-prefix:/coverImage}")
    private String coverImageUrlPrefix;

    @Value("${app.upload.audio-dir:/audio}")
    private String audioDir;

    @Value("${app.upload.audio-url-prefix:/audio}")
    private String audioUrlPrefix;

    @Value("${app.upload.avatar-dir:/avatar}")
    private String avatarDir;

    @Value("${app.upload.avatar-url-prefix:/avatar}")
    private String avatarUrlPrefix;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        register(registry, coverImageUrlPrefix, coverImageDir);
        register(registry, audioUrlPrefix, audioDir);
        register(registry, avatarUrlPrefix, avatarDir);
    }

    /**
     * 注册一组静态资源映射：{prefix}/** 与 /api{prefix}/**
     */
    private void register(ResourceHandlerRegistry registry, String urlPrefix, String dir) {
        String prefix = urlPrefix.endsWith("/")
                ? urlPrefix.substring(0, urlPrefix.length() - 1)
                : urlPrefix;
        String location = Paths.get(dir).toAbsolutePath().normalize().toUri().toString();
        // 目录形式的资源位置必须以 '/' 结尾，否则 Spring 无法正确解析
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        // 直连后端时通过 {prefix}/** 访问
        registry.addResourceHandler(prefix + "/**")
                .addResourceLocations(location);
        // 经网关（/api/** 前缀）访问时通过 /api{prefix}/** 访问
        if (!prefix.startsWith("/api/")) {
            registry.addResourceHandler("/api" + prefix + "/**")
                    .addResourceLocations(location);
        }
    }
}

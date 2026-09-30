package jack.digityhuman.onlineclassroom.config;

import jack.digityhuman.onlineclassroom.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class KngWebConfig implements WebMvcConfigurer {

    private final StorageService storageService;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api/kng-files/**")
                .addResourceLocations(storageService.root().toUri().toString());
    }
}

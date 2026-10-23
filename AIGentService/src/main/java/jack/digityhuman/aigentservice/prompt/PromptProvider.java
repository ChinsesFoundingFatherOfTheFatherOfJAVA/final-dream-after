package jack.digityhuman.aigentservice.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 版本化提示词加载器；提示词只从 classpath 读取，不在业务代码中拼接。 */
@Component
public class PromptProvider {

    private final Map<String, String> prompts = new ConcurrentHashMap<>();

    public String freePractice() {
        return load("prompts/free-practice.md");
    }

    /** 按 classpath 路径加载并缓存提示词资源，供 P2/P3 workflow 共用。 */
    public String load(String resourcePath) {
        return prompts.computeIfAbsent(resourcePath, this::read);
    }

    private String read(String resourcePath) {
        try {
            return new ClassPathResource(resourcePath)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载 prompt 失败：" + resourcePath, e);
        }
    }
}

package jack.digityhuman.aigentservice.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 版本化提示词加载器；提示词只从 classpath 读取，不在业务代码中拼接。 */
@Component
public class PromptProvider {

    private volatile String freePracticePrompt;

    public String freePractice() {
        String cached = freePracticePrompt;
        if (cached != null) {
            return cached;
        }
        try {
            cached = new ClassPathResource("prompts/free-practice.md")
                    .getContentAsString(StandardCharsets.UTF_8);
            freePracticePrompt = cached;
            return cached;
        } catch (Exception e) {
            throw new IllegalStateException("加载 free-practice prompt 失败", e);
        }
    }
}

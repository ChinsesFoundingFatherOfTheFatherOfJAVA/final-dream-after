package jack.digityhuman.speechtraining.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一异常响应的语义码测试。
 */
class GlobalExceptionHandlerTest {

    @Test
    void bizExceptionKeepsSemanticCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ApiResponse<Void> response = handler.handleBizException(new BizException(403002, "仅项目创建者可执行此操作"));

        assertThat(response.getCode()).isEqualTo(403002);
        assertThat(response.getMessage()).isEqualTo("仅项目创建者可执行此操作");
    }
}

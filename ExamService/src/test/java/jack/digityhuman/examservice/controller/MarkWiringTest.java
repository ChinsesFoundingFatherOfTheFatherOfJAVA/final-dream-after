package jack.digityhuman.examservice.controller;

import jack.digityhuman.examservice.common.AnswerPaperAssembler;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.service.impl.MarkServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批阅模块的**装配**验证：不连 Nacos 也能证明 {@code MarkServiceImpl} 的依赖能被满足。
 *
 * <p>为什么需要这个：`ExamServiceApplicationTests.contextLoads` 要连 Nacos，
 * 本机 Nacos 未启动时那个用例恒失败（AGENTS §5 明示属环境问题），于是
 * 「少了某个 mapper 依赖」这种真实的装配错误会被那条噪音盖住——
 * 全量跑完只能看到 1 个红，但红的是 Nacos 还是自己的 bean，得去翻堆栈。
 *
 * <p>只 {@code @Import} 批阅链路的三个类，**不用包扫描**：扫 `service.impl` 会把
 * `AiGenServiceImpl` 一起拉进来，它要 `DifyProperties`，于是这个测试会因为
 * 与本模块无关的依赖而失败——那样的"红"和 Nacos 那条一样是噪音。
 */
class MarkWiringTest {

    /** 只为装配验证：显式导入批阅链路的三个 bean，mapper 由 mock 代理提供。 */
    @Configuration
    @Import({ MarkServiceImpl.class, AnswerPaperAssembler.class, MarkController.class })
    static class WiringConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WiringConfig.class)
            .withBean(CurrentUserService.class,
                    () -> org.mockito.Mockito.mock(CurrentUserService.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamArrangeMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamArrangeMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamAttemptMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamAttemptMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamArrangeOperationLogMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamArrangeOperationLogMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamArrangeSignatureMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamArrangeSignatureMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamQuestionMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamQuestionMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamQuestionItemMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamQuestionItemMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamQuestionPointMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamQuestionPointMapper.class))
            .withBean(jack.digityhuman.examservice.mapper.ExamPointMapper.class,
                    () -> org.mockito.Mockito.mock(jack.digityhuman.examservice.mapper.ExamPointMapper.class));

    @Test
    void 批阅服务与装配器依赖齐全可启动() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MarkServiceImpl.class);
            assertThat(context).hasSingleBean(AnswerPaperAssembler.class);
            assertThat(context).hasSingleBean(MarkController.class);
        });
    }
}

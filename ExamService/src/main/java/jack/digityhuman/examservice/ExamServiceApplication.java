package jack.digityhuman.examservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 测练中心（题库管理）微服务入口。
 * 模块纵向文档见 D:\JACK\plan\测练中心题库管理\；本应用只拥有 exam_* 表。
 *
 * <p>{@code @EnableScheduling} 目前只服务变更通知的 outbox worker（{@code ExamNoticeOutboxWorker}）：
 * 发送动作必须与业务事务解耦，靠定时轮询重试，而不是让变更通知接口去同步等通知通道。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("jack.digityhuman.examservice.mapper")
public class ExamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExamServiceApplication.class, args);
    }

}

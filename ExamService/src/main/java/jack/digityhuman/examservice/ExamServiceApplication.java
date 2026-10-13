package jack.digityhuman.examservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 测练中心（题库管理）微服务入口。
 * 模块纵向文档见 D:\JACK\plan\测练中心题库管理\；本应用只拥有 exam_* 表。
 */
@SpringBootApplication
@MapperScan("jack.digityhuman.examservice.mapper")
public class ExamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExamServiceApplication.class, args);
    }

}

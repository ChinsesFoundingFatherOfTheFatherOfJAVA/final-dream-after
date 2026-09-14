package jack.digityhuman.speechtraining;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("jack.digityhuman.speechtraining.mapper")
public class SpeechTrainingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpeechTrainingApplication.class, args);
    }
}

package jack.digityhuman.onlineclassroom.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模拟实现：真实转码/解析专项接入时，用解析完成回调替换本定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishSimulateJob {

    private final KngCoursewareMapper coursewareMapper;

    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    public void publishFinishedCourseware() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(30);
        List<KngCourseware> pending = coursewareMapper.selectList(
                Wrappers.<KngCourseware>lambdaQuery()
                        .eq(KngCourseware::getStatus, 1)
                        .le(KngCourseware::getCreatedAt, threshold));
        for (KngCourseware courseware : pending) {
            courseware.setStatus(2);
            courseware.setOnSale(1);
            courseware.setUpdatedAt(LocalDateTime.now());
            coursewareMapper.updateById(courseware);
        }
        if (!pending.isEmpty()) {
            log.info("模拟发布完成 {} 个课件", pending.size());
        }
    }
}

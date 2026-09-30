package jack.digityhuman.onlineclassroom;

import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.service.PublishSimulateJob;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishSimulateJobTest {

    @Test
    void marksTimedPublishingCoursewareAsPublished() {
        KngCoursewareMapper mapper = mock(KngCoursewareMapper.class);
        KngCourseware courseware = new KngCourseware();
        courseware.setId("cw-1");
        courseware.setStatus(1);
        courseware.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        when(mapper.selectList(any())).thenReturn(List.of(courseware));

        new PublishSimulateJob(mapper).publishFinishedCourseware();

        assertThat(courseware.getStatus()).isEqualTo(2);
        assertThat(courseware.getOnSale()).isEqualTo(1);
        verify(mapper).updateById(courseware);
    }
}

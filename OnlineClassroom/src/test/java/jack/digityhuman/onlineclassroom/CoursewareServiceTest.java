package jack.digityhuman.onlineclassroom;

import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngSetting;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareAttachmentMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareFileMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.mapper.KngRefMapper;
import jack.digityhuman.onlineclassroom.mapper.KngSettingMapper;
import jack.digityhuman.onlineclassroom.service.CoursewareService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoursewareServiceTest {

    @Mock
    private KngCoursewareMapper coursewareMapper;
    @Mock
    private KngCoursewareFileMapper fileMapper;
    @Mock
    private KngCoursewareAttachmentMapper attachmentMapper;
    @Mock
    private KngCatalogMapper catalogMapper;
    @Mock
    private KngSettingMapper settingMapper;
    @Mock
    private KngRefMapper refMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @InjectMocks
    private CoursewareService service;

    @Test
    void followsScorePolicyInRealTimeForVideo() {
        KngCourseware courseware = new KngCourseware();
        courseware.setId("cw-1");
        courseware.setTitle("灯塔工厂方案5");
        courseware.setType(2);
        courseware.setStandardMinutes(12);
        courseware.setStandardSeconds(52);
        courseware.setStudyScore(null);

        KngSetting setting = new KngSetting();
        setting.setId(1);
        setting.setScoreConf("""
                {"videoAudio":{"standardMinutes":10,"score":1.0,"applyToAll":false},
                 "document":{"pagesPerScore":1,"score":0.1,"standardMinutes":1,"maxScore":6.0,"applyToAll":false},
                 "microcourse":{"standardMinutes":10,"score":1.0,"maxScore":10.0,"applyToAll":false},
                 "scorm":{"standardMinutes":10,"score":1.0,"applyToAll":false},
                 "html":{"standardMinutes":10,"score":1.0,"applyToAll":false},
                 "rewardType":0}
                """);

        when(coursewareMapper.selectById("cw-1")).thenReturn(courseware);
        when(settingMapper.selectById(1)).thenReturn(setting);
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(attachmentMapper.selectList(any())).thenReturn(List.of());
        when(catalogMapper.selectList(any())).thenReturn(List.of());

        KngCoursewareDtos.Detail detail = service.detail("cw-1");

        assertThat(detail.getStudyScore()).isNull();
        assertThat(detail.getStudyScoreCalc()).isEqualByComparingTo(new BigDecimal("1.29"));
    }

    @Test
    void externalLinkAlwaysCalculatesZeroFollowScore() {
        KngCourseware courseware = new KngCourseware();
        courseware.setId("cw-link");
        courseware.setTitle("外链课");
        courseware.setType(8);
        courseware.setStudyScore(null);

        KngSetting setting = new KngSetting();
        setting.setId(1);
        setting.setScoreConf("""
                {"videoAudio":{"standardMinutes":10,"score":1.0,"applyToAll":false},
                 "document":{"pagesPerScore":1,"score":0.1,"standardMinutes":1,"maxScore":6.0,"applyToAll":false},
                 "microcourse":{"standardMinutes":10,"score":1.0,"maxScore":10.0,"applyToAll":false},
                 "scorm":{"standardMinutes":10,"score":1.0,"applyToAll":false},
                 "html":{"standardMinutes":10,"score":1.0,"applyToAll":false},
                 "rewardType":0}
                """);

        when(coursewareMapper.selectById("cw-link")).thenReturn(courseware);
        when(settingMapper.selectById(1)).thenReturn(setting);
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(attachmentMapper.selectList(any())).thenReturn(List.of());
        when(catalogMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.detail("cw-link").getStudyScoreCalc())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }
}

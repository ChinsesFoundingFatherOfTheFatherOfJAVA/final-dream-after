package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.examservice.dto.req.ScoreConfigDtos;
import jack.digityhuman.examservice.entity.ExamScoreConfig;
import jack.digityhuman.examservice.mapper.ExamScoreConfigMapper;
import jack.digityhuman.examservice.service.ScoreConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认分值配置实现（07 §7）。
 *
 * <p>全量覆盖式保存：以提交清单为准 upsert，(type,levelType) 唯一。
 */
@Service
@RequiredArgsConstructor
public class ScoreConfigServiceImpl implements ScoreConfigService {

    private final ExamScoreConfigMapper scoreConfigMapper;

    @Override
    public List<ScoreConfigDtos.Item> list() {
        List<ExamScoreConfig> rows = scoreConfigMapper.selectList(Wrappers.<ExamScoreConfig>lambdaQuery()
                .orderByAsc(ExamScoreConfig::getType)
                .orderByAsc(ExamScoreConfig::getLevelType));
        List<ScoreConfigDtos.Item> items = new ArrayList<>();
        for (ExamScoreConfig row : rows) {
            ScoreConfigDtos.Item item = new ScoreConfigDtos.Item();
            item.setType(row.getType());
            item.setLevelType(row.getLevelType());
            item.setScore(row.getScore());
            item.setMissScore(row.getMissScore());
            item.setMissScoreType(row.getMissScoreType());
            items.add(item);
        }
        return items;
    }

    @Override
    @Transactional
    public void save(ScoreConfigDtos.SaveReq req) {
        if (req == null || req.getItems() == null) {
            return;
        }
        for (ScoreConfigDtos.Item item : req.getItems()) {
            if (item == null || item.getType() == null || item.getLevelType() == null) {
                continue;
            }
            ExamScoreConfig exists = scoreConfigMapper.selectOne(Wrappers.<ExamScoreConfig>lambdaQuery()
                    .eq(ExamScoreConfig::getType, item.getType())
                    .eq(ExamScoreConfig::getLevelType, item.getLevelType()));
            if (exists == null) {
                exists = new ExamScoreConfig();
                exists.setId(IdWorker.getIdStr());
                exists.setType(item.getType());
                exists.setLevelType(item.getLevelType());
                exists.setScore(item.getScore() == null ? BigDecimal.ZERO : item.getScore());
                exists.setMissScore(item.getMissScore() == null ? BigDecimal.ZERO : item.getMissScore());
                exists.setMissScoreType(item.getMissScoreType() == null ? 0 : item.getMissScoreType());
                scoreConfigMapper.insert(exists);
            } else {
                if (item.getScore() != null) {
                    exists.setScore(item.getScore());
                }
                if (item.getMissScore() != null) {
                    exists.setMissScore(item.getMissScore());
                }
                if (item.getMissScoreType() != null) {
                    exists.setMissScoreType(item.getMissScoreType());
                }
                scoreConfigMapper.updateById(exists);
            }
        }
    }
}
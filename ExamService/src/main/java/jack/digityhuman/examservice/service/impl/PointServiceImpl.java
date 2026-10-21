package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.PointDtos;
import jack.digityhuman.examservice.dto.resp.PointRespDtos;
import jack.digityhuman.examservice.entity.ExamLib;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.mapper.ExamLibMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.service.PointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 考核点实现（FP-09）。
 *
 * <p>编辑抽屉是"全量覆盖式"提交（03 §4.2）：以提交的清单为准，新增/改名/删除一起算，
 * 默认考点（建库时自动生成、default_point=1）不参与删除，避免题库失去兜底考点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointServiceImpl implements PointService {

    private final ExamPointMapper pointMapper;
    private final ExamLibMapper libMapper;

    @Override
    public List<PointRespDtos.PointItem> list(String libId) {
        if (!StringUtils.hasText(libId)) {
            return List.of();
        }
        List<ExamPoint> points = pointMapper.selectList(Wrappers.<ExamPoint>lambdaQuery()
                .eq(ExamPoint::getLibId, libId)
                .orderByDesc(ExamPoint::getDefaultPoint)
                .orderByAsc(ExamPoint::getCreatedAt));
        List<PointRespDtos.PointItem> items = new ArrayList<>();
        for (ExamPoint point : points) {
            PointRespDtos.PointItem item = new PointRespDtos.PointItem();
            item.setId(point.getId());
            item.setName(point.getName());
            item.setDefaultPoint(point.getDefaultPoint());
            items.add(item);
        }
        return items;
    }

    @Override
    @Transactional
    public void batchSave(PointDtos.BatchReq req) {
        if (req == null || !StringUtils.hasText(req.getLibId())) {
            throw new BizException("缺少题库 id");
        }
        String libId = req.getLibId();
        List<PointDtos.BatchReq.Item> incoming = req.getPoints() == null ? List.of() : req.getPoints();
        List<ExamPoint> existing = pointMapper.selectList(Wrappers.<ExamPoint>lambdaQuery()
                .eq(ExamPoint::getLibId, libId));

        Set<String> keep = new HashSet<>();
        int order = 0;
        for (PointDtos.BatchReq.Item item : incoming) {
            if (item == null || !StringUtils.hasText(item.getName())) {
                continue;
            }
            String name = item.getName().trim();
            if (StringUtils.hasText(item.getId())) {
                ExamPoint hit = existing.stream()
                        .filter(e -> e.getId().equals(item.getId()))
                        .findFirst().orElse(null);
                if (hit == null) {
                    continue;
                }
                keep.add(hit.getId());
                if (!name.equals(hit.getName())) {
                    hit.setName(name);
                    pointMapper.updateById(hit);
                }
            } else {
                ExamPoint created = new ExamPoint();
                created.setId(IdWorker.getIdStr());
                created.setLibId(libId);
                created.setName(name);
                created.setDefaultPoint(0);
                created.setCreatedAt(LocalDateTime.now());
                pointMapper.insert(created);
                keep.add(created.getId());
            }
            order++;
        }

        // 未出现在提交清单里的考点：逻辑删（默认考点豁免）
        for (ExamPoint point : existing) {
            if (keep.contains(point.getId())) {
                continue;
            }
            if (point.getDefaultPoint() != null && point.getDefaultPoint() == 1) {
                continue;
            }
            pointMapper.deleteById(point.getId());
        }

        // point_qty 是题库列表的冗余计数，增/删/改名都在这里收口重算；
        // 不重算会导致"建库时 1，之后新增考点永远是 1"（B-01）。
        Long count = pointMapper.selectCount(Wrappers.<ExamPoint>lambdaQuery()
                .eq(ExamPoint::getLibId, libId));
        ExamLib lib = libMapper.selectById(libId);
        if (lib != null) {
            lib.setPointQty(count == null ? 0 : count.intValue());
            libMapper.updateById(lib);
        }
    }
}

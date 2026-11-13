package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ArrangeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeCatalog;
import jack.digityhuman.examservice.entity.ExamArrangeOperationLog;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamPaperUse;
import jack.digityhuman.examservice.mapper.ExamArrangeCatalogMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeOperationLogMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamPaperUseMapper;
import jack.digityhuman.examservice.service.ArrangeService;
import jack.digityhuman.examservice.service.PaperService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 考试安排服务实现（施工序阶段 2）。
 *
 * <p>发布是唯一"重"事务：校验试卷已发布 → 冻结快照 → 记引用 → 改状态，全部在同一事务里，
 * 任一失败都不留半发布状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArrangeServiceImpl implements ArrangeService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 错误码（04 §2）：考试域统一使用 400xx/500xx，40101 只保留给网关"未登录"。 */
    private static final int ERR_NOT_FOUND = 40002;
    private static final int ERR_NO_PERMISSION = 40003;
    private static final int ERR_STATE = 40004;
    private static final int ERR_PAPER_NOT_RELEASED = 40005;

    private final ExamArrangeMapper arrangeMapper;
    private final ExamArrangeCatalogMapper catalogMapper;
    private final ExamPaperSnapshotMapper snapshotMapper;
    private final ExamPaperUseMapper paperUseMapper;
    private final ExamPaperMapper paperMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamArrangeOperationLogMapper logMapper;
    private final PaperService paperService;
    private final jack.digityhuman.examservice.service.ArrangePermissionService arrangePermissionService;
    private final CurrentUserService currentUserService;
    private final OrgQueryService orgQueryService;

    @Value("")
    private String linkBase;

    /** 兜底基址：@Value 在 Nacos 配置下可能读不到默认值，这里再兜一层。 */
    private static final String FALLBACK_LINK_BASE = "http://localhost:3000/#/exam/join";

    @Override
    public PageResult<ArrangeRespDtos.ArrangeItem> list(int current, int limit, String orderby,
                                                        String direction, ArrangeDtos.ListQuery query) {
        ArrangeDtos.ListQuery q = query == null ? new ArrangeDtos.ListQuery() : query;
        var w = Wrappers.<ExamArrange>lambdaQuery();
        if (q.getExamType() != null) {
            w.eq(ExamArrange::getExamType, q.getExamType());
        }
        if (q.getPublishStatus() != null && q.getPublishStatus() >= 0) {
            // 对外 0 未发布 / 2 进行中 / 3 已结束 → DB publish_status 0 / 1 / 2
            int ps = q.getPublishStatus();
            w.eq(ExamArrange::getPublishStatus, ps == 0 ? 0 : ps == 2 ? 1 : 2);
        }
        if (StringUtils.hasText(q.getCatalogId()) && !"root".equals(q.getCatalogId())) {
            // 含子分类：先按 routing_path 前缀取到子树 id 集合，再 in 过滤（考试表本身不存 routing_path）
            List<String> catalogIds = new ArrayList<>();
            catalogIds.add(q.getCatalogId());
            if (StringUtils.hasText(q.getRoutingPath())) {
                for (ExamArrangeCatalog child : catalogMapper.selectList(Wrappers.<ExamArrangeCatalog>lambdaQuery()
                        .likeRight(ExamArrangeCatalog::getRoutingPath, q.getRoutingPath()))) {
                    catalogIds.add(child.getId());
                }
            }
            w.in(ExamArrange::getCatalogId, catalogIds);
        }
        if (StringUtils.hasText(q.getKeyword())) {
            // F3：关键词同时匹配 考试名称 / 考试编号 / 创建人
            String kw = q.getKeyword().trim();
            w.and(x -> x.like(ExamArrange::getName, kw)
                    .or().like(ExamArrange::getCode, kw)
                    .or().like(ExamArrange::getCreateFullname, kw));
        }
        // 多选创建人（原平台支持多选）：非空时优先，单值 createUserId 保留兼容
        List<String> creatorIds = q.getCreateUserIds() == null ? List.of()
                : q.getCreateUserIds().stream()
                        .filter(StringUtils::hasText).map(String::trim).distinct().toList();
        if (!creatorIds.isEmpty()) {
            w.in(ExamArrange::getCreateUserId, creatorIds);
        } else if (StringUtils.hasText(q.getCreateUserId())) {
            w.eq(ExamArrange::getCreateUserId, q.getCreateUserId().trim());
        }
        if (q.getArrangeType() != null && q.getArrangeType() >= 0) {
            w.eq(ExamArrange::getArrangeType, q.getArrangeType());
        }
        String uid = currentUserService.userId(null);
        if (q.getQueryType() != null && q.getQueryType() == 1) {
            w.eq(ExamArrange::getCreateUserId, uid);
        } else if (q.getQueryType() != null && q.getQueryType() == 2) {
            // 我负责的：owner_user_ids 是逗号分隔的 id 串
            if (StringUtils.hasText(uid) && !"0".equals(uid)) {
                w.like(ExamArrange::getOwnerUserIds, uid);
            }
        } else if (!arrangePermissionService.seesAll(uid)) {
            // 非超管：只看"自己创建的 + 自己可管分类（含继承子树）里的"（F23 可见范围）
            java.util.Set<String> managedCatalogs = arrangePermissionService.manageableCatalogIds(uid);
            w.and(x -> {
                x.eq(ExamArrange::getCreateUserId, uid);
                if (!managedCatalogs.isEmpty()) {
                    x.or().in(ExamArrange::getCatalogId, managedCatalogs);
                }
            });
        }
        boolean asc = "asc".equalsIgnoreCase(direction);
        // F3 排序白名单：创建时间 / 入场时间 / 安排人数 / 已交卷 / 已通过 / 通过率
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<ExamArrange, ?> sortColumn =
                switch (orderby == null ? "" : orderby) {
                    case "createdAt" -> ExamArrange::getCreatedAt;
                    case "entryStartAt" -> ExamArrange::getEntryStartAt;
                    case "arrangedQty" -> ExamArrange::getParticipantQty;
                    case "submittedQty" -> ExamArrange::getSubmittedQty;
                    case "passedQty" -> ExamArrange::getPassedQty;
                    case "passRate" -> ExamArrange::getPassRate;
                    default -> ExamArrange::getUpdatedAt;
                };
        w.orderBy(true, asc, sortColumn);

        IPage<ExamArrange> page = arrangeMapper.selectPage(new Page<>(current, limit), w);
        List<ArrangeRespDtos.ArrangeItem> items = new ArrayList<>();
        for (ExamArrange row : page.getRecords()) {
            items.add(toItem(row));
        }
        return PageResult.of(items, page.getTotal());
    }

    @Override
    public ArrangeRespDtos.ArrangeDetail detail(String id) {
        ExamArrange row = require(id);
        ArrangeRespDtos.ArrangeDetail d = new ArrangeRespDtos.ArrangeDetail();
        copyBase(row, d);
        d.setCode(row.getCode());
        d.setImportant(row.getImportant());
        d.setSecrecy(row.getSecrecy());
        d.setLoopEnabled(row.getLoopEnabled());
        d.setRequiredType(row.getRequiredType());
        d.setPassScoreType(row.getPassScoreType());
        d.setPassScore(row.getPassScore());
        d.setPassRatio(row.getPassRatio());
        d.setRewardType(row.getRewardType());
        d.setRewardCredit(row.getRewardCredit());
        d.setAttemptLimit(row.getAttemptLimit());
        d.setLateLimitMin(row.getLateLimitMin());
        d.setScreenSwitchLimit(row.getScreenSwitchLimit());
        d.setStepNumber(row.getStepNumber());
        d.setPublishedAt(row.getPublishedAt() == null ? null : TIME.format(row.getPublishedAt()));
        d.setNotifySettings(readJson(row.getNotifySettings()));
        d.setPublishSettings(readJson(row.getPublishSettings()));
        d.setAdvancedSettings(readJson(row.getAdvancedSettings()));
        ExamPaper paper = StringUtils.hasText(row.getPaperId()) ? paperMapper.selectById(row.getPaperId()) : null;
        if (paper != null) {
            d.setPaperName(paper.getName());
            d.setPaperScore(paper.getTotalScore());
        }
        if (StringUtils.hasText(row.getLinkToken())) {
            d.setLinkUrl((StringUtils.hasText(linkBase) ? linkBase : FALLBACK_LINK_BASE) + "?token=" + row.getLinkToken());
            d.setQrCodeUrl(d.getLinkUrl());
        }
        return d;
    }

    @Override
    @Transactional
    public String create(ArrangeDtos.SaveReq req) {
        validate(req);
        ExamArrange row = new ExamArrange();
        row.setId(IdWorker.getIdStr());
        applySave(row, req);
        // paper_id 在 DDL 里是 NOT NULL 且无默认值：未选试卷时先存空串，发布前再校验
        row.setPaperId(req.getPaperId() == null ? "" : req.getPaperId());
        row.setExamType(1);
        row.setPublishStatus(0);
        row.setRunStatus(0);
        row.setAuditStatus(0);
        row.setArrangeType(0);
        row.setOffline(0);
        row.setStepNumber(req.getStepNumber() == null ? 1 : req.getStepNumber());
        row.setDeleted(0);
        row.setTotalScore(BigDecimal.ZERO);
        row.setParticipantQty(0);
        row.setSubmittedQty(0);
        row.setPassedQty(0);
        row.setPassRate(BigDecimal.ZERO);
        row.setAnalysisEnabled(0);
        row.setLinkToken(UUID.randomUUID().toString().replace("-", ""));
        String uid = currentUserService.userId(null);
        row.setCreateUserId(uid);
        row.setCreatedBy(uid);
        row.setCreateFullname(operatorName());
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        arrangeMapper.insert(row);
        log(row.getId(), "create", null);
        return row.getId();
    }

    @Override
    @Transactional
    public void update(String id, ArrangeDtos.SaveReq req) {
        ExamArrange row = require(id);
        if (!canManage(row)) {
            throw new BizException(ERR_NO_PERMISSION, "无编辑权限");
        }
        boolean published = row.getPublishStatus() != null && row.getPublishStatus() > 0;
        if (published) {
            // 已发布：只允许改白名单字段（施工提示词 §6 阶段 3）
            if (req.getName() != null) row.setName(req.getName());
            if (req.getImportant() != null) row.setImportant(req.getImportant());
            if (req.getSecrecy() != null) row.setSecrecy(req.getSecrecy());
            if (req.getCatalogId() != null) row.setCatalogId(req.getCatalogId());
            row.setEntryStartAt(parseTime(req.getEntryStartAt()));
            row.setEntryEndAt(parseTime(req.getEntryEndAt()));
            if (req.getPassScore() != null) row.setPassScore(req.getPassScore());
            if (req.getPassRatio() != null) row.setPassRatio(req.getPassRatio());
            if (req.getOwnerUserIds() != null) row.setOwnerUserIds(req.getOwnerUserIds());
            if (req.getOwnerNames() != null) row.setOwnerNames(req.getOwnerNames());
            if (req.getNotifySettings() != null) row.setNotifySettings(req.getNotifySettings());
            if (req.getAdvancedSettings() != null) row.setAdvancedSettings(req.getAdvancedSettings());
            // 优秀分数/考试说明/批阅人存 publish_settings，F18.1 允许发布后修改
            if (req.getPublishSettings() != null) row.setPublishSettings(req.getPublishSettings());
            // 2026-10-21 决策（D-31）：已发布考试锁定「使用试卷」，禁止换卷。
            // 原因：答卷读取的是发布时冻结的 exam_paper_snapshot，换卷会让同一考试的新旧答卷
            // 指向不同题目集合，成绩与正确率无法横向比较；需要换卷请新建考试。
            if (req.getPaperId() != null && !req.getPaperId().equals(row.getPaperId())) {
                throw new BizException(ERR_STATE, "已发布考试不可更换试卷");
            }
        } else {
            applySave(row, req);
            if (req.getStepNumber() != null) {
                row.setStepNumber(req.getStepNumber());
            }
        }
        row.setUpdatedBy(currentUserService.userId(null));
        row.setUpdatedAt(LocalDateTime.now());
        arrangeMapper.updateById(row);
        log(id, "update", null);
    }

    @Override
    @Transactional
    public void publish(String id) {
        ExamArrange row = require(id);
        if (!canManage(row)) {
            throw new BizException(ERR_NO_PERMISSION, "无发布权限");
        }
        if (row.getPublishStatus() != null && row.getPublishStatus() > 0) {
            return; // 幂等
        }
        if (!StringUtils.hasText(row.getPaperId())) {
            throw new BizException(ERR_STATE, "请先选择试卷");
        }
        ExamPaper paper = paperMapper.selectById(row.getPaperId());
        if (paper == null) {
            throw new BizException(ERR_PAPER_NOT_RELEASED, "试卷不存在或已删除");
        }
        if (paper.getStatus() == null || paper.getStatus() != 4) {
            // 2026-10-20 用户确认：点「发布考试」时自动发布所绑定的试卷。
            // 原来要求先在试卷管理里发布，流程被“未发布试卷”硬生生打断；这里复用试卷发布校验（题目/策略 + 分值）。
            paperService.release(row.getPaperId());
            paper = paperMapper.selectById(row.getPaperId());
            if (paper == null || paper.getStatus() == null || paper.getStatus() != 4) {
                throw new BizException(ERR_PAPER_NOT_RELEASED, "试卷发布失败，请检查试卷题目与分值");
            }
        }
        // 1) 冻结快照（后续改卷不影响历史答卷）
        ExamPaperSnapshot snapshot = new ExamPaperSnapshot();
        snapshot.setId(IdWorker.getIdStr());
        snapshot.setArrangeId(id);
        snapshot.setPaperId(paper.getId());
        snapshot.setVersionNo(nextVersion(id, paper.getId()));
        snapshot.setSnapshotJson(buildSnapshot(paper));
        snapshot.setCreatedBy(currentUserService.userId(null));
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insert(snapshot);

        // 2) 记试卷引用（引用次数由 paper.used_qty 维护）
        ExamPaperUse use = new ExamPaperUse();
        use.setId(IdWorker.getIdStr());
        use.setPaperId(paper.getId());
        // 引用方（考试）与类型：试卷列表的引用统计、试卷模块的"被考试引用"查询都依赖这两列
        use.setRefId(id);
        use.setRefType(1);
        use.setRefName(row.getName());
        use.setSourceType(1);
        use.setCreatorName(row.getCreateFullname());
        use.setStatus("已发布");
        use.setCreatedAt(LocalDateTime.now());
        use.setUpdatedAt(LocalDateTime.now());
        paperUseMapper.insert(use);
        paper.setUsedQty((paper.getUsedQty() == null ? 0 : paper.getUsedQty()) + 1);
        paperMapper.updateById(paper);

        // 3) 改状态
        row.setPublishStatus(1);
        row.setAuditStatus(2);
        row.setStepNumber(3);
        row.setRunStatus(row.getRunStatus() == null || row.getRunStatus() == 0 ? 1 : row.getRunStatus());
        row.setPublishedAt(LocalDateTime.now());
        row.setTotalScore(paper.getTotalScore());
        row.setUpdatedBy(currentUserService.userId(null));
        row.setUpdatedAt(LocalDateTime.now());
        arrangeMapper.updateById(row);
        log(id, "publish", snapshot.getId());
    }

    @Override
    @Transactional
    public void end(String id) {
        ExamArrange row = require(id);
        if (!canManage(row)) {
            throw new BizException(ERR_NO_PERMISSION, "无结束权限");
        }
        row.setPublishStatus(2);
        row.setRunStatus(2);
        row.setEndedAt(LocalDateTime.now());
        row.setUpdatedBy(currentUserService.userId(null));
        row.setUpdatedAt(LocalDateTime.now());
        arrangeMapper.updateById(row);
        log(id, "end", null);
    }

    @Override
    @Transactional
    public String copy(String id) {
        ExamArrange src = require(id);
        ExamArrange row = new ExamArrange();
        row.setId(IdWorker.getIdStr());
        row.setName(src.getName() + "（副本）");
        row.setCatalogId(src.getCatalogId());
        row.setExamType(src.getExamType());
        row.setPaperId(src.getPaperId());
        row.setPaperBuildType(src.getPaperBuildType());
        row.setCode(src.getCode());
        row.setImportant(src.getImportant());
        row.setSecrecy(src.getSecrecy());
        row.setLoopEnabled(src.getLoopEnabled());
        row.setEntryStartAt(src.getEntryStartAt());
        row.setEntryEndAt(src.getEntryEndAt());
        row.setRequiredType(src.getRequiredType());
        row.setPassScoreType(src.getPassScoreType());
        row.setPassScore(src.getPassScore());
        row.setPassRatio(src.getPassRatio());
        row.setRewardType(src.getRewardType());
        row.setRewardCredit(src.getRewardCredit());
        row.setAttemptLimit(src.getAttemptLimit());
        row.setNotifySettings(src.getNotifySettings());
        row.setPublishSettings(src.getPublishSettings());
        row.setAdvancedSettings(src.getAdvancedSettings());
        row.setOwnerUserIds(src.getOwnerUserIds());
        row.setOwnerNames(src.getOwnerNames());
        // 复制出来的是草稿：状态/进度/统计全部归零
        row.setPublishStatus(0);
        row.setRunStatus(0);
        row.setAuditStatus(0);
        row.setArrangeType(0);
        row.setOffline(0);
        row.setStepNumber(3);
        row.setTotalScore(src.getTotalScore());
        row.setParticipantQty(0);
        row.setSubmittedQty(0);
        row.setPassedQty(0);
        row.setPassRate(BigDecimal.ZERO);
        row.setAnalysisEnabled(0);
        row.setLinkToken(UUID.randomUUID().toString().replace("-", ""));
        String uid = currentUserService.userId(null);
        row.setCreateUserId(uid);
        row.setCreatedBy(uid);
        row.setCreateFullname(operatorName());
        row.setDeleted(0);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        arrangeMapper.insert(row);
        log(row.getId(), "copy", id);
        return row.getId();
    }

    @Override
    @Transactional
    public void delete(String id) {
        ExamArrange row = require(id);
        if (!canManage(row)) {
            throw new BizException(ERR_NO_PERMISSION, "无删除权限");
        }
        assertDeletable(row);
        arrangeMapper.deleteById(id);
        log(id, "delete", null);
    }

    /**
     * F23 / 04 §4：进行中的考试与已有作答记录的考试不可删除。
     *
     * <p>已结束且无作答记录的考试允许删除（用于清理误建/测试数据）；软删除由 {@code @TableLogic} 完成。
     */
    private void assertDeletable(ExamArrange row) {
        if (row.getPublishStatus() != null && row.getPublishStatus() == 1) {
            throw new BizException(ERR_STATE, "进行中的考试不可删除，请先结束考试");
        }
        Long attempts = attemptMapper.selectCount(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, row.getId()));
        if (attempts != null && attempts > 0) {
            throw new BizException(ERR_STATE, "该考试已有作答记录，不可删除");
        }
    }

    @Override
    @Transactional
    public void batch(ArrangeDtos.BatchReq req) {
        if (req == null || req.getIds() == null || req.getIds().isEmpty()) {
            throw new BizException("请先勾选考试");
        }
        for (String id : req.getIds()) {
            if ("end".equals(req.getAction())) {
                end(id);
            } else {
                delete(id);
            }
        }
    }

    @Override
    public ArrangeRespDtos.LinkResp link(String id) {
        ExamArrange row = require(id);
        String token = row.getLinkToken();
        if (!StringUtils.hasText(token)) {
            token = UUID.randomUUID().toString().replace("-", "");
            row.setLinkToken(token);
            arrangeMapper.updateById(row);
        }
        ArrangeRespDtos.LinkResp resp = new ArrangeRespDtos.LinkResp();
        resp.setToken(token);
        resp.setLinkUrl((StringUtils.hasText(linkBase) ? linkBase : FALLBACK_LINK_BASE) + "?token=" + token);
        resp.setQrCodeUrl(resp.getLinkUrl());
        resp.setJoinQty(0);
        return resp;
    }

    // ---------------- 内部 ----------------

    /** 入参是 "yyyy-MM-dd HH:mm:ss" 字符串，这里显式解析（Jackson 3 对 LocalDateTime 的默认解析不吃带空格格式）。 */
    private LocalDateTime parseTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim().replace('T', ' '), TIME);
        } catch (Exception e) {
            throw new BizException("时间格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
    }
    private ExamArrange require(String id) {
        ExamArrange row = arrangeMapper.selectById(id);
        if (row == null) {
            throw new BizException(ERR_NOT_FOUND, "考试不存在或已删除");
        }
        return row;
    }

    private boolean canManage(ExamArrange row) {
        // F23 判定链：超管 → 创建者 → 所属分类管理员（含继承），统一收敛到权限服务
        return arrangePermissionService.canManage(row);
    }

    private void validate(ArrangeDtos.SaveReq req) {
        if (req == null || !StringUtils.hasText(req.getName())) {
            throw new BizException("请输入考试名称");
        }
        if (req.getName().length() > 1000) {
            throw new BizException("考试名称过长");
        }
        // 入场时间：结束必须晚于开始（F18 校验项）
        LocalDateTime entryStart = parseTime(req.getEntryStartAt());
        LocalDateTime entryEnd = parseTime(req.getEntryEndAt());
        if (entryStart != null && entryEnd != null && !entryEnd.isAfter(entryStart)) {
            throw new BizException("入场结束时间需晚于开始时间");
        }
        // 通过分数：固定分 / 比例两种口径分别校验范围
        if (req.getPassScoreType() != null && req.getPassScoreType() == 2) {
            if (req.getPassRatio() != null && (req.getPassRatio().compareTo(BigDecimal.ZERO) < 0
                    || req.getPassRatio().compareTo(BigDecimal.ONE) > 0)) {
                throw new BizException("通过比例需在 0~1 之间");
            }
        } else if (req.getPassScore() != null && req.getPassScore().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("通过分数不能为负数");
        }
        // 固定 IP 考试：开启时必须给 IP 列表（F18 校验项）
        if (StringUtils.hasText(req.getAdvancedSettings())) {
            Map<String, Object> advanced = readJson(req.getAdvancedSettings());
            Object fixedIp = advanced.get("fixedIp");
            if (fixedIp instanceof Number n && n.intValue() == 1) {
                Object list = advanced.get("fixedIpList");
                boolean empty = !(list instanceof List<?> l) || l.isEmpty();
                if (empty) {
                    throw new BizException("开启固定 IP 考试后需填写 IP 列表");
                }
            }
        }
    }

    private void applySave(ExamArrange row, ArrangeDtos.SaveReq req) {
        row.setName(req.getName().trim());
        row.setCatalogId(StringUtils.hasText(req.getCatalogId()) ? req.getCatalogId() : ExamArrangeCatalog.ROOT_ID);
        if (req.getPaperId() != null) {
            row.setPaperId(req.getPaperId());
        }
        if (req.getPaperBuildType() != null) {
            row.setPaperBuildType(req.getPaperBuildType());
        }
        if (req.getCode() != null) row.setCode(req.getCode());
        if (req.getImportant() != null) row.setImportant(req.getImportant());
        if (req.getSecrecy() != null) row.setSecrecy(req.getSecrecy());
        if (req.getLoopEnabled() != null) row.setLoopEnabled(req.getLoopEnabled());
        row.setEntryStartAt(parseTime(req.getEntryStartAt()));
        row.setEntryEndAt(parseTime(req.getEntryEndAt()));
        if (req.getRequiredType() != null) row.setRequiredType(req.getRequiredType());
        if (req.getPassScoreType() != null) row.setPassScoreType(req.getPassScoreType());
        if (req.getPassScore() != null) row.setPassScore(req.getPassScore());
        if (req.getPassRatio() != null) row.setPassRatio(req.getPassRatio());
        if (req.getRewardType() != null) row.setRewardType(req.getRewardType());
        if (req.getRewardCredit() != null) row.setRewardCredit(req.getRewardCredit());
        if (req.getAttemptLimit() != null) row.setAttemptLimit(req.getAttemptLimit());
        if (req.getLateLimitMin() != null) row.setLateLimitMin(req.getLateLimitMin());
        if (req.getScreenSwitchLimit() != null) row.setScreenSwitchLimit(req.getScreenSwitchLimit());
        if (req.getNotifySettings() != null) row.setNotifySettings(req.getNotifySettings());
        if (req.getPublishSettings() != null) row.setPublishSettings(req.getPublishSettings());
        if (req.getAdvancedSettings() != null) row.setAdvancedSettings(req.getAdvancedSettings());
        if (req.getOwnerUserIds() != null) row.setOwnerUserIds(req.getOwnerUserIds());
        if (req.getOwnerNames() != null) {
            row.setOwnerNames(req.getOwnerNames());
            String first = req.getOwnerNames().split("[;；]")[0];
            row.setOwnerName(first == null ? "" : first);
        }
        if (req.getOwnerUserIds() != null) {
            String firstId = req.getOwnerUserIds().split(",")[0];
            row.setOwnerUserId(firstId == null ? "" : firstId);
        }
    }

    private void copyBase(ExamArrange row, ArrangeRespDtos.ArrangeItem item) {
        item.setId(row.getId());
        item.setName(row.getName());
        item.setCatalogId(row.getCatalogId());
        item.setCatalogName(catalogName(row.getCatalogId()));
        item.setExamType(row.getExamType());
        item.setStatus(displayStatus(row));
        item.setAuditStatus(row.getAuditStatus());
        item.setArrangeType(row.getArrangeType());
        item.setOffline(row.getOffline());
        item.setPaperId(row.getPaperId());
        item.setPaperBuildType(row.getPaperBuildType());
        item.setEntryStartAt(row.getEntryStartAt() == null ? null : TIME.format(row.getEntryStartAt()));
        item.setEntryEndAt(row.getEntryEndAt() == null ? null : TIME.format(row.getEntryEndAt()));
        item.setCreateUserId(row.getCreateUserId());
        /*
         * 创建人姓名（2026-11-13 修）：优先用 Dubbo 实时查到的真实姓名。
         *
         * <p>**问题**：`exam_arrange.create_fullname` 里存在历史脏数据 —— 实测
         * admin 这条记录存的是 `?????`（HEX 3F3F3F3F3F），即当初写入时
         * 中文被字符集转换成了问号，而 `sys_user.name` 里是正确的「超级管理员」。
         * 直接回显该列会让页面显示一串问号，用户根本不知道创建人是谁。
         *
         * <p>**修法**：拿 createUserId 走 OrgQueryService 取真名；查不到才回退列值。
         * 只改读取侧：写入侧的脏数据已存在，回填要靠数据修复脚本，不在读接口里做。
         */
        item.setCreateFullname(resolveCreatorName(row));
        item.setCreateUserName(row.getCreateUserName());
        item.setCreatedAt(row.getCreatedAt() == null ? null : TIME.format(row.getCreatedAt()));
        item.setOwnerUserIds(split(row.getOwnerUserIds(), ","));
        item.setOwnerNames(split(row.getOwnerNames(), "[;；]"));
        item.setArrangedQty(row.getParticipantQty());
        item.setSubmittedQty(row.getSubmittedQty());
        item.setPassedQty(row.getPassedQty());
        item.setPassRate(row.getPassRate());
        ArrangeRespDtos.Permissions p = new ArrangeRespDtos.Permissions();
        boolean manage = canManage(row);
        p.setCanEdit(manage);
        p.setCanDelete(manage);
        p.setCanPublish(manage);
        p.setCanEnd(manage);
        item.setPermissions(p);
    }

    private ArrangeRespDtos.ArrangeItem toItem(ExamArrange row) {
        ArrangeRespDtos.ArrangeItem item = new ArrangeRespDtos.ArrangeItem();
        copyBase(row, item);
        return item;
    }

    /**
     * 列表/详情对外状态（04 §1.2）：0 未发布、2 进行中、3 已结束。
     *
     * <p>DB 存的是 publish_status（0 未发布 / 1 已发布 / 2 已结束）+ run_status，
     * 对外要转换成文档约定的显示态，前端状态列与筛选都按 0/2/3 走。
     */
    private Integer displayStatus(ExamArrange row) {
        Integer ps = row.getPublishStatus();
        if (ps == null || ps == 0) {
            return 0;
        }
        if (ps >= 2 || (row.getRunStatus() != null && row.getRunStatus() == 2)) {
            return 3;
        }
        return 2;
    }

    private String catalogName(String catalogId) {
        if (!StringUtils.hasText(catalogId)) {
            return "";
        }
        ExamArrangeCatalog c = catalogMapper.selectById(catalogId);
        return c == null ? "" : c.getName();
    }

    private List<String> split(String raw, String regex) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(regex)).filter(StringUtils::hasText).toList();
    }

    private Map<String, Object> readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(raw, JSON.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private int nextVersion(String arrangeId, String paperId) {
        Long n = snapshotMapper.selectCount(Wrappers.<ExamPaperSnapshot>lambdaQuery()
                .eq(ExamPaperSnapshot::getArrangeId, arrangeId)
                .eq(ExamPaperSnapshot::getPaperId, paperId));
        return (n == null ? 0 : n.intValue()) + 1;
    }

    /** 快照内容：试卷基本信息 + 关联题目（题面/分值/大题），历史答卷按它判分。 */
    private String buildSnapshot(ExamPaper paper) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("paperId", paper.getId());
        snap.put("paperName", paper.getName());
        snap.put("buildType", paper.getBuildType());
        snap.put("quesMode", paper.getQuesMode());
        snap.put("totalScore", paper.getTotalScore());
        List<Map<String, Object>> questions = new ArrayList<>();
        for (ExamPaperQuestion rel : paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paper.getId())
                .orderByAsc(ExamPaperQuestion::getSectionIndex)
                .orderByAsc(ExamPaperQuestion::getOrderIndex))) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("quesId", rel.getQuesId());
            q.put("sectionIndex", rel.getSectionIndex());
            q.put("orderIndex", rel.getOrderIndex());
            q.put("score", rel.getScore());
            q.put("missScore", rel.getMissScore());
            q.put("missScoreType", rel.getMissScoreType());
            q.put("subScores", rel.getSubScores());
            questions.add(q);
        }
        snap.put("questions", questions);
        snap.put("snapshotAt", LocalDateTime.now().toString());
        try {
            return JSON.writeValueAsString(snap);
        } catch (Exception e) {
            throw new BizException("生成试卷快照失败");
        }
    }

        /** 操作人姓名：走 Dubbo 查（查不到就留空，不阻断业务）。 */
    private String operatorName() {
        try {
            String uid = currentUserService.userId(null);
            if (!StringUtils.hasText(uid) || "0".equals(uid)) {
                return "";
            }
            var users = orgQueryService.listUsersByIds(List.of(Long.parseLong(uid)));
            return users.isEmpty() || users.get(0).getName() == null ? "" : users.get(0).getName();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解析创建人显示名（2026-11-13）。
     *
     * <p>优先走 Dubbo 用 createUserId 取实时姓名，取不到才回退 `create_fullname` 列。
     *
     * <p>为什么不能直接用列值：`exam_arrange.create_fullname` 存在历史脏数据，
     * 中文在写入时被转成了问号（实测 admin 记录为 `?????`，HEX 3F3F3F3F3F），
     * 而 `sys_user.name` 里是正确的「超级管理员」。读接口直接回显就是一串问号。
     *
     * <p>任何异常都吞掉并回退列值 —— 详情页不能因为查不到用户名而整页失败。
     */
    private String resolveCreatorName(ExamArrange row) {
        String fallback = row.getCreateFullname();
        try {
            String uid = row.getCreateUserId();
            if (!StringUtils.hasText(uid) || "0".equals(uid)) {
                return fallback;
            }
            var users = orgQueryService.listUsersByIds(List.of(Long.parseLong(uid)));
            if (users != null && !users.isEmpty() && StringUtils.hasText(users.get(0).getName())) {
                return users.get(0).getName();
            }
        } catch (Exception ignored) {
            // 查询失败/用户已删：回退到列值，不让详情页报错
        }
        return fallback;
    }

    private void log(String arrangeId, String action, String targetId) {
        try {
            ExamArrangeOperationLog row = new ExamArrangeOperationLog();
            row.setId(IdWorker.getIdStr());
            row.setArrangeId(arrangeId);
            row.setAction(action);
            row.setTargetType("arrange");
            row.setTargetId(targetId == null ? "" : targetId);
            row.setOperatorId(currentUserService.userId(null));
            row.setOperatorName(operatorName());
            row.setCreatedAt(LocalDateTime.now());
            logMapper.insert(row);
        } catch (Exception e) {
            log.warn("写考试操作日志失败: {}", e.getMessage());
        }
    }
}

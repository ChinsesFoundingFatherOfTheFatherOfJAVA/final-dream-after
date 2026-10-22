package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.PaperDtos;
import jack.digityhuman.examservice.dto.resp.PaperRespDtos;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamPaperCatalog;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperShare;
import jack.digityhuman.examservice.entity.ExamPaperUse;
import jack.digityhuman.examservice.entity.ExamPaperStrategy;
import jack.digityhuman.examservice.mapper.ExamPaperCatalogMapper;
import jack.digityhuman.examservice.mapper.ExamPaperMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperShareMapper;
import jack.digityhuman.examservice.mapper.ExamPaperUseMapper;
import jack.digityhuman.examservice.mapper.ExamPaperStrategyMapper;
import jack.digityhuman.examservice.service.PaperService;
import jack.digityhuman.examservice.service.PaperStrategyService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.examservice.dto.req.ExamLibDtos;
import jack.digityhuman.examservice.dto.resp.ExamLibRespDtos;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 试卷服务实现（FP-05/06）。
 *
 * <p>与题库 LibService 同口径：列表带 permissions 能力集（创建者/超管），创建人姓名经 Dubbo 补齐，
 * 分类过滤含子分类；查询维度支持「全部 / 我创建的 / 共享给我的」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperServiceImpl implements PaperService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExamPaperMapper paperMapper;
    private final ExamPaperCatalogMapper paperCatalogMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamPaperStrategyMapper paperStrategyMapper;
    private final ExamPaperShareMapper paperShareMapper;
    private final ExamPaperUseMapper paperUseMapper;
    private final PaperStrategyService paperStrategyService;
    private final OrgQueryService orgQueryService;
    private final CurrentUserService currentUserService;

    @Override
    public PageResult<PaperRespDtos.PaperItem> list(int current, int limit, String orderby,
                                                    String direction, PaperDtos.ListQuery query) {
        String operator = currentUserService.userId(null);
        var w = Wrappers.<ExamPaper>lambdaQuery();

        // 分类过滤：含子分类（catalog_id 命中子树）
        if (query != null && StringUtils.hasText(query.getCatalogId()) && !"root".equals(query.getCatalogId())) {
            ExamPaperCatalog catalog = paperCatalogMapper.selectById(query.getCatalogId());
            String prefix = catalog == null ? "" : catalog.getRoutingPath();
            if (StringUtils.hasText(query.getRoutingPath())) {
                prefix = query.getRoutingPath();
            }
            if (StringUtils.hasText(prefix)) {
                List<String> subtreeIds = new ArrayList<>(catalogIdsUnderPrefix(prefix));
                subtreeIds.add(query.getCatalogId());
                w.in(ExamPaper::getCatalogId, subtreeIds);
            } else {
                w.eq(ExamPaper::getCatalogId, query.getCatalogId());
            }
        }

        int queryType = query == null || query.getQueryType() == null ? 0 : query.getQueryType();
        if (queryType == 1) {
            w.eq(ExamPaper::getCreatedBy, operator);
        } else if (queryType == 2) {
            List<String> sharedIds = sharedPaperIdsOf(operator);
            if (sharedIds.isEmpty()) {
                return PageResult.of(List.of(), 0L);
            }
            w.in(ExamPaper::getId, sharedIds).ne(ExamPaper::getCreatedBy, operator);
        }

        if (query != null && StringUtils.hasText(query.getCreatorUserId())) {
            w.eq(ExamPaper::getCreatedBy, query.getCreatorUserId());
        }
        if (query != null && query.getBuildType() != null && query.getBuildType() >= 0) {
            w.eq(ExamPaper::getBuildType, query.getBuildType());
        }
        if (query != null && query.getDisabled() != null && query.getDisabled() >= 0) {
            w.eq(ExamPaper::getDisabled, query.getDisabled());
        }
        if (query != null && query.getStatus() != null && query.getStatus() >= 0) {
            w.eq(ExamPaper::getStatus, query.getStatus());
        }
        if (query != null && StringUtils.hasText(query.getKeyword())) {
            w.like(ExamPaper::getName, query.getKeyword().trim());
        }

        boolean asc = "asc".equalsIgnoreCase(String.valueOf(direction));
        String ob = orderby == null ? "updateTime" : orderby;
        switch (ob) {
            case "usedQty" -> w.orderBy(true, asc, ExamPaper::getUsedQty);
            case "createTime" -> w.orderBy(true, asc, ExamPaper::getCreatedAt);
            default -> w.orderBy(true, asc, ExamPaper::getUpdatedAt);
        }

        IPage<ExamPaper> page = paperMapper.selectPage(new Page<>(current, limit), w);
        List<ExamPaper> records = page.getRecords();
        Map<String, String> catalogNames = catalogNamesOf(records);
        Map<String, String> userNames = userNamesOf(records);

        List<PaperRespDtos.PaperItem> items = new ArrayList<>();
        for (ExamPaper paper : records) {
            items.add(toItem(paper, catalogNames, userNames, operator));
        }
        return PageResult.of(items, page.getTotal());
    }

    @Override
    public PaperRespDtos.PaperDetail detail(String id) {
        ExamPaper paper = requirePaper(id);
        String operator = currentUserService.userId(null);
        PaperRespDtos.PaperDetail d = new PaperRespDtos.PaperDetail();
        copy(toItem(paper, catalogNamesOf(List.of(paper)), userNamesOf(List.of(paper)), operator), d);
        d.setQuesMode(paper.getQuesMode());
        return d;
    }

    @Override
    @Transactional
    public String create(PaperDtos.SaveReq req) {
        validate(req);
        String id = IdWorker.getIdStr();
        String operator = currentUserService.userId(null);
        ExamPaper paper = new ExamPaper();
        paper.setId(id);
        paper.setName(req.getName().trim());
        paper.setCatalogId(StringUtils.hasText(req.getCatalogId()) ? req.getCatalogId() : "root");
        paper.setBuildType(req.getBuildType() == null ? 0 : req.getBuildType());
        paper.setSecrecy(req.getSecrecy() == null ? 0 : req.getSecrecy());
        paper.setStatus(0);
        paper.setQuesMode(0);
        paper.setDisabled(0);
        paper.setTotalScore(BigDecimal.ZERO);
        paper.setTotalQuesQty(0);
        paper.setUsedQty(0);
        paper.setDeleted(0);
        paper.setCreatedBy(operator);
        paper.setCreatedByName(fullnameOf(operator));
        paper.setCreatedAt(LocalDateTime.now());
        paper.setUpdatedBy(operator);
        paper.setUpdatedAt(LocalDateTime.now());
        paperMapper.insert(paper);
        return id;
    }

    @Override
    @Transactional
    public void update(String id, PaperDtos.SaveReq req) {
        ExamPaper paper = requirePaper(id);
        validate(req);
        paper.setName(req.getName().trim());
        paper.setCatalogId(StringUtils.hasText(req.getCatalogId()) ? req.getCatalogId() : "root");
        if (req.getBuildType() != null) {
            paper.setBuildType(req.getBuildType());
        }
        if (req.getSecrecy() != null) {
            paper.setSecrecy(req.getSecrecy());
        }
        paper.setUpdatedBy(currentUserService.userId(null));
        paper.setUpdatedAt(LocalDateTime.now());
        paperMapper.updateById(paper);
    }

    @Override
    @Transactional
    public void disable(String id, int disabled) {
        ExamPaper paper = requirePaper(id);
        paper.setDisabled(disabled);
        paper.setUpdatedBy(currentUserService.userId(null));
        paper.setUpdatedAt(LocalDateTime.now());
        paperMapper.updateById(paper);
    }

    @Override
    @Transactional
    public void delete(String id) {
        ExamPaper paper = requirePaper(id);
        String operator = currentUserService.userId(null);
        if (!canManage(paper, operator)) {
            throw new BizException(40003, "无删除权限");
        }
        // 级联逻辑删：题目关联 / 抽题策略 / 共享范围（07 §2.4）
        paperQuestionMapper.delete(Wrappers.<ExamPaperQuestion>lambdaQuery().eq(ExamPaperQuestion::getPaperId, id));
        paperStrategyMapper.delete(Wrappers.<ExamPaperStrategy>lambdaQuery().eq(ExamPaperStrategy::getPaperId, id));
        paperShareMapper.delete(Wrappers.<ExamPaperShare>lambdaQuery().eq(ExamPaperShare::getPaperId, id));
        paperMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void transfer(String id, String toUserId) {
        ExamPaper paper = requirePaper(id);
        if (!StringUtils.hasText(toUserId)) {
            throw new BizException("请选择新创建者");
        }
        paper.setCreatedBy(toUserId);
        paper.setCreatedByName(fullnameOf(toUserId));
        paper.setUpdatedBy(currentUserService.userId(null));
        paper.setUpdatedAt(LocalDateTime.now());
        paperMapper.updateById(paper);
    }

    @Override
    @Transactional
    public void changeCatalog(String id, String catalogId) {
        ExamPaper paper = requirePaper(id);
        if (!StringUtils.hasText(catalogId)) {
            throw new BizException("请选择分类");
        }
        paper.setCatalogId(catalogId);
        paper.setUpdatedBy(currentUserService.userId(null));
        paper.setUpdatedAt(LocalDateTime.now());
        paperMapper.updateById(paper);
    }

    @Override
    @Transactional
    public void release(String id) {
        ExamPaper paper = requirePaper(id);
        if (paper.getStatus() != null && paper.getStatus() == 4) {
            return; // 已发布，幂等
        }
        // 发布校验链（07 §2.6）：① 先有题目（固定卷）或策略（随机卷） ② 再要求有分值
        boolean ready;
        if (paper.getBuildType() != null && paper.getBuildType() == 1) {
            ready = paperStrategyMapper.selectCount(Wrappers.<ExamPaperStrategy>lambdaQuery()
                    .eq(ExamPaperStrategy::getPaperId, id)) > 0;
            if (ready) {
                var summary = paperStrategyService.summary(id);
                if (summary.getQuesQty() == null || summary.getQuesQty() <= 0) {
                    throw new BizException(40051, "请先设置抽取数量");
                }
                if (summary.getTotalScore() == null || summary.getTotalScore().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException(40052, "请先设置分值");
                }
            }
        } else {
            ready = paperQuestionMapper.selectCount(Wrappers.<ExamPaperQuestion>lambdaQuery()
                    .eq(ExamPaperQuestion::getPaperId, id)) > 0;
        }
        if (!ready) {
            throw new BizException(40051, "请先设置策略或添加试题");
        }
        if (paper.getTotalScore() == null || paper.getTotalScore().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(40052, "请先设置分值");
        }
        paper.setStatus(4);
        paper.setReleaseTime(LocalDateTime.now());
        paper.setUpdatedBy(currentUserService.userId(null));
        paper.setUpdatedAt(LocalDateTime.now());
        paperMapper.updateById(paper);
    }

    @Override
    public ExamLibRespDtos.ShareInfo getShare(String id) {
        requirePaper(id);
        List<ExamPaperShare> rels = paperShareMapper.selectList(Wrappers.<ExamPaperShare>lambdaQuery()
                .eq(ExamPaperShare::getPaperId, id));
        ExamLibRespDtos.ShareInfo info = new ExamLibRespDtos.ShareInfo();
        info.setLibId(id);

        // 人名走 Dubbo 批量补；部门名同样经 Dubbo 取（与题库共享口径一致）
        Map<String, String> userNames = new HashMap<>();
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> deptIds = new LinkedHashSet<>();
        for (ExamPaperShare rel : rels) {
            try {
                if (rel.getTargetType() != null && rel.getTargetType() == 2) {
                    userIds.add(Long.parseLong(rel.getTargetId()));
                } else if (rel.getTargetType() != null && rel.getTargetType() == 1) {
                    deptIds.add(Long.parseLong(rel.getTargetId()));
                }
            } catch (NumberFormatException ignored) {
                // 非法目标 id 跳过
            }
        }
        if (!userIds.isEmpty()) {
            try {
                for (SysUserDTO user : orgQueryService.listUsersByIds(new ArrayList<>(userIds))) {
                    userNames.put(String.valueOf(user.getId()), user.getName());
                }
            } catch (Exception e) {
                log.warn("补齐共享人姓名失败: {}", e.getMessage());
            }
        }
        Map<String, String> deptNames = new HashMap<>();
        if (!deptIds.isEmpty()) {
            try {
                for (var dept : orgQueryService.listDepartmentsByIds(new ArrayList<>(deptIds))) {
                    deptNames.put(String.valueOf(dept.getId()), dept.getDeptName());
                }
            } catch (Exception e) {
                log.warn("补齐共享部门名失败: {}", e.getMessage());
            }
        }

        List<ExamLibRespDtos.ShareInfo.ShareRow> rows = new ArrayList<>();
        for (ExamPaperShare rel : rels) {
            ExamLibRespDtos.ShareInfo.ShareRow row = new ExamLibRespDtos.ShareInfo.ShareRow();
            row.setTargetType(rel.getTargetType());
            row.setTargetId(rel.getTargetId());
            row.setTargetName(rel.getTargetType() != null && rel.getTargetType() == 1
                    ? deptNames.getOrDefault(rel.getTargetId(), rel.getTargetId())
                    : userNames.getOrDefault(rel.getTargetId(), rel.getTargetId()));
            rows.add(row);
        }
        info.setShares(rows);
        return info;
    }

    @Override
    @Transactional
    public void saveShare(String id, ExamLibDtos.ShareReq req) {
        ExamPaper paper = requirePaper(id);
        // 共享仅对已发布开放（07 §2.5 / 40030）
        if (paper.getStatus() == null || paper.getStatus() != 4) {
            throw new BizException(40030, "试卷未发布，不能共享");
        }
        paperShareMapper.delete(Wrappers.<ExamPaperShare>lambdaQuery().eq(ExamPaperShare::getPaperId, id));
        if (req == null || req.getShares() == null) {
            return;
        }
        for (ExamLibDtos.ShareReq.ShareItem item : req.getShares()) {
            if (item == null || !StringUtils.hasText(item.getTargetId())) {
                continue;
            }
            ExamPaperShare rel = new ExamPaperShare();
            rel.setId(IdWorker.getIdStr());
            rel.setPaperId(id);
            rel.setTargetType(item.getTargetType() == null ? 2 : item.getTargetType());
            rel.setTargetId(item.getTargetId());
            paperShareMapper.insert(rel);
        }
    }

    @Override
    public List<PaperRespDtos.PaperUseItem> useList(String id) {
        requirePaper(id);
        List<ExamPaperUse> uses = paperUseMapper.selectList(Wrappers.<ExamPaperUse>lambdaQuery()
                .eq(ExamPaperUse::getPaperId, id)
                .orderByDesc(ExamPaperUse::getCreatedAt));
        List<PaperRespDtos.PaperUseItem> items = new ArrayList<>();
        for (ExamPaperUse use : uses) {
            PaperRespDtos.PaperUseItem item = new PaperRespDtos.PaperUseItem();
            item.setId(use.getId());
            item.setRefName(use.getRefName());
            item.setSourceType(use.getSourceType());
            item.setCreatorName(use.getCreatorName());
            item.setStatus(use.getStatus());
            item.setCreatedAt(use.getCreatedAt() == null ? "" : use.getCreatedAt().format(TIME));
            items.add(item);
        }
        return items;
    }
    @Override
    @Transactional
    public void updateQuesMode(String id, int quesMode) {
        ExamPaper paper = requirePaper(id);
        if (paper.getStatus() != null && paper.getStatus() == 4) {
            throw new BizException(40050, "试卷已发布，不可修改策略");
        }
        if (quesMode < 0 || quesMode > 3) {
            throw new BizException("抽题方式不合法");
        }
        paper.setQuesMode(quesMode);
        paper.setUpdatedBy(currentUserService.userId(null));
        paper.setUpdatedAt(LocalDateTime.now());
        paperMapper.updateById(paper);
    }

    /**
     * 2.4 批量操作（07 §2.4）：沿用单条逻辑，逐条校验权限/合法性。
     * 逐条失败不中断其余条目——但删除/转移这种有权限语义的动作，
     * 第一条无权限就抛错，避免前端误以为"全部成功"。
     */
    @Override
    @Transactional
    public void batch(PaperDtos.BatchReq req) {
        if (req == null || req.getPaperIds() == null || req.getPaperIds().isEmpty()) {
            throw new BizException("请先勾选试卷");
        }
        String action = req.getAction() == null ? "" : req.getAction();
        for (String id : req.getPaperIds()) {
            switch (action) {
                case "delete" -> delete(id);
                case "transfer" -> transfer(id, req.getToUserId());
                case "changeCatalog" -> changeCatalog(id, req.getCatalogId());
                default -> throw new BizException("不支持的批量操作");
            }
        }
    }

    @Override
    public byte[] exportExcel(PaperDtos.ListQuery query) {
        // 复用同一条查询链：导出即"当前筛选条件下的全部页"
        PageResult<PaperRespDtos.PaperItem> all = list(1, 10000, "updateTime", "desc", query);
        String[] titles = { "试卷名称", "出卷方式", "引用次数", "创建人", "更新日期", "试卷状态", "启用" };
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("试卷列表");
            Row header = sheet.createRow(0);
            for (int i = 0; i < titles.length; i++) {
                header.createCell(i).setCellValue(titles[i]);
            }
            int rowIndex = 1;
            for (PaperRespDtos.PaperItem item : all.getRecords()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(item.getName());
                row.createCell(1).setCellValue(item.getBuildType() != null && item.getBuildType() == 1 ? "随机出卷" : "固定出卷");
                row.createCell(2).setCellValue(item.getUsedQty() == null ? 0 : item.getUsedQty());
                row.createCell(3).setCellValue(item.getCreateFullname() == null ? "" : item.getCreateFullname());
                row.createCell(4).setCellValue(item.getUpdateTime() == null ? "" : item.getUpdateTime());
                row.createCell(5).setCellValue(item.getStatus() != null && item.getStatus() == 4 ? "已发布" : "已保存");
                row.createCell(6).setCellValue(item.getDisabled() != null && item.getDisabled() == 1 ? "禁用" : "启用");
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("导出失败，请重试");
        }
    }
    // ---------------- 内部 ----------------

    private ExamPaper requirePaper(String id) {
        ExamPaper paper = paperMapper.selectById(id);
        if (paper == null) {
            throw new BizException("试卷不存在或已删除");
        }
        return paper;
    }

    private void validate(PaperDtos.SaveReq req) {
        if (req == null || !StringUtils.hasText(req.getName())) {
            throw new BizException("请输入试卷名称");
        }
        if (req.getName().trim().length() > 200) {
            throw new BizException("试卷名称200字以内");
        }
    }

    private boolean canManage(ExamPaper paper, String operator) {
        return operator.equals(paper.getCreatedBy()) || currentUserService.isSuperAdmin(null);
    }

    private PaperRespDtos.PaperItem toItem(ExamPaper paper, Map<String, String> catalogNames,
                                          Map<String, String> userNames, String operator) {
        PaperRespDtos.PaperItem item = new PaperRespDtos.PaperItem();
        copyBasic(paper, item, catalogNames, userNames);
        PaperRespDtos.Permissions p = new PaperRespDtos.Permissions();
        boolean owner = operator.equals(paper.getCreatedBy());
        boolean admin = currentUserService.isSuperAdmin(null);
        p.setCanDelete(owner || admin);
        p.setCanEdit(owner || admin);
        item.setPermissions(p);
        return item;
    }

    private void copy(PaperRespDtos.PaperItem from, PaperRespDtos.PaperDetail to) {
        to.setId(from.getId());
        to.setName(from.getName());
        to.setCatalogId(from.getCatalogId());
        to.setCatalogName(from.getCatalogName());
        to.setBuildType(from.getBuildType());
        to.setStatus(from.getStatus());
        to.setDisabled(from.getDisabled());
        to.setSecrecy(from.getSecrecy());
        to.setTotalScore(from.getTotalScore());
        to.setTotalQuesQty(from.getTotalQuesQty());
        to.setUsedQty(from.getUsedQty());
        to.setCreateUserId(from.getCreateUserId());
        to.setCreateFullname(from.getCreateFullname());
        to.setCreateUserName(from.getCreateUserName());
        to.setUpdateTime(from.getUpdateTime());
        to.setPermissions(from.getPermissions());
    }

    private void copyBasic(ExamPaper paper, PaperRespDtos.PaperItem item,
                           Map<String, String> catalogNames, Map<String, String> userNames) {
        item.setId(paper.getId());
        item.setName(paper.getName());
        item.setCatalogId(paper.getCatalogId());
        item.setCatalogName(catalogNames.getOrDefault(paper.getCatalogId(), "默认试卷分类"));
        item.setBuildType(paper.getBuildType());
        item.setStatus(paper.getStatus() == null ? 0 : paper.getStatus());
        item.setDisabled(paper.getDisabled());
        item.setSecrecy(paper.getSecrecy());
        item.setTotalScore(paper.getTotalScore() == null ? BigDecimal.ZERO : paper.getTotalScore());
        item.setTotalQuesQty(paper.getTotalQuesQty() == null ? 0 : paper.getTotalQuesQty());
        item.setUsedQty(paper.getUsedQty() == null ? 0 : paper.getUsedQty());
        item.setCreateUserId(paper.getCreatedBy());
        item.setCreateFullname(userNames.getOrDefault(paper.getCreatedBy(), paper.getCreatedByName()));
        item.setCreateUserName("");
        item.setUpdateTime(paper.getUpdatedAt() == null ? "" : paper.getUpdatedAt().format(TIME));
    }

    private Map<String, String> catalogNamesOf(List<ExamPaper> papers) {
        Map<String, String> names = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ExamPaper paper : papers) {
            if (StringUtils.hasText(paper.getCatalogId())) {
                ids.add(paper.getCatalogId());
            }
        }
        if (!ids.isEmpty()) {
            for (ExamPaperCatalog catalog : paperCatalogMapper.selectBatchIds(ids)) {
                names.put(catalog.getId(), catalog.getName());
            }
        }
        return names;
    }

    private Map<String, String> userNamesOf(List<ExamPaper> papers) {
        Map<String, String> names = new HashMap<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (ExamPaper paper : papers) {
            if (StringUtils.hasText(paper.getCreatedBy())) {
                try {
                    ids.add(Long.parseLong(paper.getCreatedBy()));
                } catch (NumberFormatException ignored) {
                    // "0" 之类的系统占位不是真实用户
                }
            }
        }
        if (!ids.isEmpty()) {
            try {
                for (SysUserDTO user : orgQueryService.listUsersByIds(new ArrayList<>(ids))) {
                    names.put(String.valueOf(user.getId()), user.getName());
                }
            } catch (Exception e) {
                log.warn("补齐试卷创建人姓名失败: {}", e.getMessage());
            }
        }
        return names;
    }

    /** 分类前缀（含）下的全部分类 id。 */
    private List<String> catalogIdsUnderPrefix(String prefix) {
        return paperCatalogMapper.selectList(Wrappers.<ExamPaperCatalog>lambdaQuery()
                        .likeRight(ExamPaperCatalog::getRoutingPath, prefix))
                .stream().map(ExamPaperCatalog::getId).toList();
    }

    /**
     * 「共享给我的」试卷 id：人维度直接命中；部门维度经用户所属部门匹配。
     * 试卷共享仅对已发布开放（写入侧校验），这里只做读取。
     */
    private List<String> sharedPaperIdsOf(String userId) {
        List<ExamPaperShare> rels = paperShareMapper.selectList(Wrappers.<ExamPaperShare>lambdaQuery()
                .eq(ExamPaperShare::getTargetType, 2)
                .eq(ExamPaperShare::getTargetId, userId));
        List<String> ids = new ArrayList<>(rels.stream().map(ExamPaperShare::getPaperId).toList());
        try {
            for (SysUserDTO user : orgQueryService.listUsersByIds(List.of(Long.parseLong(userId)))) {
                if (!StringUtils.hasText(user.getDeptPath())) {
                    continue;
                }
                List<ExamPaperShare> deptRels = paperShareMapper.selectList(Wrappers.<ExamPaperShare>lambdaQuery()
                        .eq(ExamPaperShare::getTargetType, 1)
                        .eq(ExamPaperShare::getTargetId, user.getDeptCode()));
                deptRels.forEach(rel -> ids.add(rel.getPaperId()));
            }
        } catch (Exception e) {
            // userId 非数字（"0" 占位）或 Dubbo 不可达：只回人维度
        }
        return ids.stream().distinct().toList();
    }

    private String fullnameOf(String userId) {
        try {
            List<SysUserDTO> users = orgQueryService.listUsersByIds(List.of(Long.parseLong(userId)));
            return users.isEmpty() ? "" : users.get(0).getName();
        } catch (Exception e) {
            return "";
        }
    }
}

package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ExamLibDtos;
import jack.digityhuman.examservice.dto.resp.ExamLibRespDtos;
import jack.digityhuman.examservice.entity.ExamCatalog;
import jack.digityhuman.examservice.entity.ExamLib;
import jack.digityhuman.examservice.entity.ExamLibShare;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamCatalogMapper;
import jack.digityhuman.examservice.mapper.ExamLibMapper;
import jack.digityhuman.examservice.mapper.ExamLibShareMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.LibService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题库服务实现（FP-04）。
 *
 * <p>列表口径：分类过滤 = catalog_id=? OR routing_path LIKE '前缀%'（含子分类，03 §2.1）；
 * 共享给我的 = lib_id IN (exam_lib_share 按 userId + 部门展开)；
 * permissions 能力集按当前用户计算（删除=创建者或超管；编辑=创建者或（非保密库的被共享者））。
 * 本期超管判定沿用工程现状（userId=1 视为超管占位，正式权限码接入后替换——见 06-实施记录）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LibServiceImpl implements LibService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExamLibMapper libMapper;
    private final ExamLibShareMapper libShareMapper;
    private final ExamCatalogMapper catalogMapper;
    private final ExamPointMapper pointMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionItemMapper questionItemMapper;
    private final ExamQuestionPointMapper questionPointMapper;
    private final OrgQueryService orgQueryService;
    private final CurrentUserService currentUserService;

    @Override
    public PageResult<ExamLibRespDtos.LibItem> list(int current, int limit, String orderby,
                                                    String direction, ExamLibDtos.ListQuery query) {
        String operator = currentUserService.userId(null);
        var w = Wrappers.<ExamLib>lambdaQuery();

        // 分类过滤：含子分类（catalog_id OR routing_path LIKE 前缀%）
        if (query != null && query.getCatalogId() != null && !query.getCatalogId().isBlank()
                && !"root".equals(query.getCatalogId())) {
            ExamCatalog catalog = catalogMapper.selectById(query.getCatalogId());
            String prefix = catalog == null ? "" : catalog.getRoutingPath();
            if (query.getRoutingPath() != null && !query.getRoutingPath().isBlank()) {
                prefix = query.getRoutingPath();
            }
            if (!prefix.isEmpty()) {
                // 分类过滤（含子分类）：自身 OR 前缀下全部分类的 id 集合（Lambda 内 List 已是 effectively final）
                List<String> subtreeIds = new java.util.ArrayList<>(catalogIdsUnderPrefix(prefix));
                subtreeIds.add(query.getCatalogId());
                w.in(ExamLib::getCatalogId, subtreeIds);
            } else {
                w.eq(ExamLib::getCatalogId, query.getCatalogId());
            }
        }

        // 我创建的 / 共享给我的
        int queryType = query == null || query.getQueryType() == null ? 0 : query.getQueryType();
        if (queryType == 1) {
            w.eq(ExamLib::getCreatedBy, operator);
        } else if (queryType == 2) {
            List<String> sharedLibIds = sharedLibIdsOf(operator);
            if (sharedLibIds.isEmpty()) {
                return PageResult.of(List.of(), 0);
            }
            w.in(ExamLib::getId, sharedLibIds).ne(ExamLib::getCreatedBy, operator);
        }

        // 创建人筛选
        if (query != null && query.getCreatorUserId() != null && !query.getCreatorUserId().isBlank()) {
            w.eq(ExamLib::getCreatedBy, query.getCreatorUserId());
        }
        // 启用状态
        if (query != null && query.getDisabled() != null && query.getDisabled() >= 0) {
            w.eq(ExamLib::getDisabled, query.getDisabled());
        }
        // 名称搜索
        if (query != null && query.getKeyword() != null && !query.getKeyword().isBlank()) {
            w.like(ExamLib::getName, query.getKeyword().trim());
        }

        // 排序（默认更新时间倒序；试题数/更新日期可排序）
        boolean asc = "asc".equalsIgnoreCase(direction);
        String ob = orderby == null ? "updateTime" : orderby;
        switch (ob) {
            case "totalQuesQty" -> w.orderBy(true, asc, ExamLib::getTotalQuesQty);
            case "createTime" -> w.orderBy(true, asc, ExamLib::getCreatedAt);
            default -> w.orderBy(true, asc, ExamLib::getUpdatedAt);
        }

        Page<ExamLib> page = libMapper.selectPage(new Page<>(current, limit), w);
        List<ExamLibRespDtos.LibItem> items = new ArrayList<>();
        Set<String> catalogIds = new HashSet<>();
        for (ExamLib lib : page.getRecords()) {
            catalogIds.add(lib.getCatalogId());
        }
        Map<String, String> catalogNames = catalogNames(catalogIds);
        // 共享标记（共享给我的 tab 或列表 shared 字段）
        Set<String> sharedIds = new HashSet<>(sharedLibIdsOf(operator));
        for (ExamLib lib : page.getRecords()) {
            items.add(toItem(lib, catalogNames, sharedIds, operator));
        }
        return PageResult.of(items, (int) page.getTotal());
    }

    @Override
    public ExamLibRespDtos.LibDetail detail(String id) {
        ExamLib lib = requireLib(id);
        ExamLibRespDtos.LibDetail d = new ExamLibRespDtos.LibDetail();
        d.setId(lib.getId());
        d.setName(lib.getName());
        d.setCatalogId(lib.getCatalogId());
        d.setCatalogName(catalogNames(Set.of(lib.getCatalogId())).getOrDefault(lib.getCatalogId(), "默认题库分类"));
        d.setSecrecy(lib.getSecrecy());
        d.setDefaultLib(lib.getDefaultLib());
        d.setDisabled(lib.getDisabled());
        d.setPointQty(lib.getPointQty());
        d.setTotalQuesQty(lib.getTotalQuesQty());
        d.setCreateUserId(lib.getCreatedBy());
        d.setCreateFullname(lib.getCreatedByName());
        d.setUpdateTime(lib.getUpdatedAt() == null ? null : lib.getUpdatedAt().format(TIME));
        d.setPermissions(permissionsOf(lib, currentUserService.userId(null)));
        ExamCatalog catalog = "root".equals(lib.getCatalogId()) ? null : catalogMapper.selectById(lib.getCatalogId());
        d.setCatDisabled(catalog == null ? 0 : catalog.getDisabled());
        return d;
    }

    @Override
    @Transactional
    public String create(ExamLibDtos.SaveReq req) {
        validateSave(req);
        String operator = currentUserService.userId(null);
        ExamLib lib = new ExamLib();
        lib.setName(req.getName().trim());
        lib.setCatalogId(req.getCatalogId() == null || req.getCatalogId().isBlank()
                ? "root" : req.getCatalogId());
        lib.setSecrecy(req.getSecrecy() != null && req.getSecrecy() == 1 ? 1 : 0);
        lib.setDefaultLib(0);
        lib.setDisabled(0);
        lib.setDuplicateCondition(0);
        lib.setPointQty(1);
        lib.setTotalQuesQty(0);
        lib.setDeleted(0);
        lib.setCreatedBy(operator);
        lib.setCreatedByName(fullnameOf(operator));
        lib.setCreatedAt(LocalDateTime.now());
        libMapper.insert(lib);
        // 每库自动建唯一"默认考点"（01 FP-09）
        ExamPoint point = new ExamPoint();
        point.setLibId(lib.getId());
        point.setName("默认考点");
        point.setDefaultPoint(1);
        point.setDeleted(0);
        pointMapper.insert(point);
        return lib.getId();
    }

    @Override
    public void update(String id, ExamLibDtos.SaveReq req) {
        ExamLib lib = requireLib(id);
        // 保密库编辑权：仅创建者及超管（FP-05）
        String operator = currentUserService.userId(null);
        if (lib.getSecrecy() != null && lib.getSecrecy() == 1 && !canManageSecrecy(lib, operator)) {
            throw new BizException(40003, "保密题库仅创建者及超级管理员可编辑");
        }
        validateSave(req);
        lib.setName(req.getName().trim());
        if (req.getCatalogId() != null && !req.getCatalogId().isBlank()) {
            lib.setCatalogId(req.getCatalogId());
        }
        if (req.getSecrecy() != null) {
            lib.setSecrecy(req.getSecrecy());
        }
        lib.setUpdatedBy(operator);
        libMapper.updateById(lib);
    }

    @Override
    public void disable(String id, int disabled) {
        ExamLib lib = requireLib(id);
        lib.setDisabled(disabled);
        lib.setUpdatedBy(currentUserService.userId(null));
        libMapper.updateById(lib);
    }

    @Override
    @Transactional
    public void delete(String id) {
        ExamLib lib = requireLib(id);
        String operator = currentUserService.userId(null);
        if (!canManageSecrecy(lib, operator)) {
            throw new BizException(40003, "无删除权限");
        }
        // 级联逻辑删：试题（含组合题子题）/选项/考核点关联/共享范围/考核点
        List<ExamQuestion> questions = questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getLibId, id));
        List<String> quesIds = questions.stream().map(ExamQuestion::getId).toList();
        if (!quesIds.isEmpty()) {
            questionMapper.deleteBatchIds(quesIds);
            questionItemMapper.delete(Wrappers.<ExamQuestionItem>lambdaQuery()
                    .in(ExamQuestionItem::getQuesId, quesIds));
            questionPointMapper.delete(Wrappers.<ExamQuestionPoint>lambdaQuery()
                    .in(ExamQuestionPoint::getQuesId, quesIds));
        }
        libShareMapper.delete(Wrappers.<ExamLibShare>lambdaQuery().eq(ExamLibShare::getLibId, id));
        pointMapper.delete(Wrappers.<ExamPoint>lambdaQuery().eq(ExamPoint::getLibId, id));
        libMapper.deleteById(id);
    }

    @Override
    public ExamLibRespDtos.ShareInfo getShare(String id) {
        requireLib(id);
        List<ExamLibShare> rels = libShareMapper.selectList(Wrappers.<ExamLibShare>lambdaQuery()
                .eq(ExamLibShare::getLibId, id));
        ExamLibRespDtos.ShareInfo info = new ExamLibRespDtos.ShareInfo();
        info.setLibId(id);
        List<ExamLibRespDtos.ShareInfo.ShareRow> rows = new ArrayList<>();
        // 人名经 Dubbo 批量补；部门名取部门表
        List<Long> userIds = rels.stream()
                .filter(r -> r.getTargetType() == 2)
                .map(r -> Long.parseLong(r.getTargetId()))
                .toList();
        Map<String, String> userNames = new HashMap<>();
        if (!userIds.isEmpty()) {
            try {
                for (SysUserDTO dto : orgQueryService.listUsersByIds(userIds)) {
                    userNames.put(String.valueOf(dto.getId()), dto.getName());
                }
            } catch (Exception e) {
                log.warn("补齐共享人姓名失败: {}", e.getMessage());
            }
        }
        Set<Long> deptIds = new HashSet<>();
        for (ExamLibShare r : rels) {
            if (r.getTargetType() == 1) {
                try {
                    deptIds.add(Long.parseLong(r.getTargetId()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Map<String, String> deptNames = new HashMap<>();
        if (!deptIds.isEmpty()) {
            try {
                for (var d : orgQueryService.listDepartmentsByIds(new ArrayList<>(deptIds))) {
                    deptNames.put(String.valueOf(d.getId()), d.getDeptName());
                }
            } catch (Exception e) {
                log.warn("补齐共享部门名失败: {}", e.getMessage());
            }
        }
        for (ExamLibShare r : rels) {
            ExamLibRespDtos.ShareInfo.ShareRow row = new ExamLibRespDtos.ShareInfo.ShareRow();
            row.setTargetType(r.getTargetType());
            row.setTargetId(r.getTargetId());
            row.setTargetName(r.getTargetType() == 2
                    ? userNames.getOrDefault(r.getTargetId(), "")
                    : deptNames.getOrDefault(r.getTargetId(), ""));
            rows.add(row);
        }
        info.setShares(rows);
        return info;
    }

    @Override
    @Transactional
    public void saveShare(String id, ExamLibDtos.ShareReq req) {
        requireLib(id);
        libShareMapper.delete(Wrappers.<ExamLibShare>lambdaQuery().eq(ExamLibShare::getLibId, id));
        if (req.getShares() == null) {
            return;
        }
        for (ExamLibDtos.ShareReq.ShareItem item : req.getShares()) {
            if (item.getTargetId() == null || item.getTargetId().isBlank()) {
                continue;
            }
            ExamLibShare rel = new ExamLibShare();
            rel.setLibId(id);
            rel.setTargetType(item.getTargetType() == null ? 2 : item.getTargetType());
            rel.setTargetId(item.getTargetId());
            libShareMapper.insert(rel);
        }
    }

    @Override
    public void transfer(String id, String toUserId) {
        ExamLib lib = requireLib(id);
        if (toUserId == null || toUserId.isBlank()) {
            throw new BizException("请选择新创建者");
        }
        lib.setCreatedBy(toUserId);
        lib.setCreatedByName(fullnameOf(toUserId));
        lib.setUpdatedBy(currentUserService.userId(null));
        libMapper.updateById(lib);
    }

    @Override
    public void changeCatalog(String id, String catalogId) {
        ExamLib lib = requireLib(id);
        if (catalogId == null || catalogId.isBlank()) {
            throw new BizException("请选择分类");
        }
        lib.setCatalogId(catalogId);
        lib.setUpdatedBy(currentUserService.userId(null));
        libMapper.updateById(lib);
    }

    @Override
    @Transactional
    public void batch(ExamLibDtos.BatchReq req) {
        if (req.getLibIds() == null || req.getLibIds().isEmpty()) {
            throw new BizException("请先勾选题库");
        }
        String action = req.getAction() == null ? "" : req.getAction();
        switch (action) {
            case "delete" -> req.getLibIds().forEach(this::delete);
            case "transfer" -> req.getLibIds().forEach(id -> transfer(id, req.getToUserId()));
            case "changeCatalog" -> req.getLibIds().forEach(id -> changeCatalog(id, req.getCatalogId()));
            default -> throw new BizException("不支持的批量操作: " + action);
        }
    }

    @Override
    public ExamLibRespDtos.DuplicateCondition duplicateCondition(String id) {
        ExamLib lib = requireLib(id);
        int bits = lib.getDuplicateCondition() == null ? 0 : lib.getDuplicateCondition();
        ExamLibRespDtos.DuplicateCondition condition = new ExamLibRespDtos.DuplicateCondition();
        condition.setSingleStatus((bits & 1) != 0 ? 1 : 0);
        condition.setMultiStatus((bits & 2) != 0 ? 1 : 0);
        condition.setJudgeStatus((bits & 4) != 0 ? 1 : 0);
        condition.setFillStatus((bits & 8) != 0 ? 1 : 0);
        condition.setAnswerStatus((bits & 16) != 0 ? 1 : 0);
        return condition;
    }
    // ---- 内部工具 ----

    private ExamLib requireLib(String id) {
        ExamLib lib = libMapper.selectById(id);
        if (lib == null) {
            throw new BizException("题库不存在或已删除");
        }
        return lib;
    }

    private void validateSave(ExamLibDtos.SaveReq req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException("请输入题库名称");
        }
        if (req.getName().trim().length() > 200) {
            throw new BizException("题库名称200字以内");
        }
    }

    /** 前缀（含）下的全部分类 id（routing_path LIKE 前缀 或 等于，需先查分类表再取 id 集合）。 */
    private List<String> catalogIdsUnderPrefix(String prefix) {
        List<ExamCatalog> all = catalogMapper.selectList(Wrappers.<ExamCatalog>lambdaQuery()
                .likeRight(ExamCatalog::getRoutingPath, prefix));
        return all.stream().map(ExamCatalog::getId).toList();
    }

    /** 当前用户被共享的 lib_id 集合（人维度 + 部门维度经用户所属部门匹配）。 */
    private List<String> sharedLibIdsOf(String userId) {
        List<ExamLibShare> rels = libShareMapper.selectList(Wrappers.<ExamLibShare>lambdaQuery()
                .eq(ExamLibShare::getTargetType, 2)
                .eq(ExamLibShare::getTargetId, userId));
        List<String> ids = new ArrayList<>(rels.stream().map(ExamLibShare::getLibId).toList());
        // 部门维度：查用户所属部门 → 匹配部门共享
        try {
            long uid = Long.parseLong(userId);
            var user = orgQueryService.listUsersByIds(List.of(uid));
            if (!user.isEmpty()) {
                String deptName = user.get(0).getDeptName();
                if (deptName != null && !deptName.isBlank()) {
                    // 共享目标是部门 id——部门名→id 需查部门表；取全量部门里名字匹配的
                    for (var dept : orgQueryService.listAllDepartments()) {
                        if (deptName.equals(dept.getDeptName())) {
                            List<ExamLibShare> deptRels = libShareMapper.selectList(
                                    Wrappers.<ExamLibShare>lambdaQuery()
                                            .eq(ExamLibShare::getTargetType, 1)
                                            .eq(ExamLibShare::getTargetId, String.valueOf(dept.getId())));
                            deptRels.forEach(r -> ids.add(r.getLibId()));
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // userId 非数字（"0" 系统占位）或 Dubbo 不可达：只回人维度
        }
        return ids.stream().distinct().toList();
    }

    /** permissions 能力集（03 §2.1）：删除=创建者/超管；编辑=创建者 或 非保密库被共享者。 */
    private ExamLibRespDtos.Permissions permissionsOf(ExamLib lib, String operator) {
        ExamLibRespDtos.Permissions p = new ExamLibRespDtos.Permissions();
        boolean owner = operator.equals(lib.getCreatedBy());
        boolean superAdmin = isSuperAdmin();
        p.setCanDelete(owner || superAdmin);
        boolean sharedUser = sharedLibIdsOf(operator).contains(lib.getId());
        p.setCanEdit(owner || superAdmin
                || (lib.getSecrecy() != null && lib.getSecrecy() == 0 && sharedUser));
        return p;
    }

    private boolean canManageSecrecy(ExamLib lib, String operator) {
        return operator.equals(lib.getCreatedBy()) || isSuperAdmin();
    }

    /**
     * 超管判定：读 StaffManagement 签发的 JWT 的 isSuperAdmin claim。
     * 旧实现硬编码 userId=="1"，而内置超管 account=admin 的 userId 实际是 13，判定永远不成立。
     */
    private boolean isSuperAdmin() {
        return currentUserService.isSuperAdmin(null);
    }

    private ExamLibRespDtos.LibItem toItem(ExamLib lib, Map<String, String> catalogNames,
                                           Set<String> sharedIds, String operator) {
        ExamLibRespDtos.LibItem item = new ExamLibRespDtos.LibItem();
        item.setId(lib.getId());
        item.setName(lib.getName());
        item.setCatalogId(lib.getCatalogId());
        item.setCatalogName(catalogNames.getOrDefault(lib.getCatalogId(), "默认题库分类"));
        item.setSecrecy(lib.getSecrecy());
        item.setDefaultLib(lib.getDefaultLib());
        item.setDisabled(lib.getDisabled());
        item.setPointQty(lib.getPointQty());
        item.setTotalQuesQty(lib.getTotalQuesQty());
        item.setCreateUserId(lib.getCreatedBy());
        item.setCreateFullname(lib.getCreatedByName());
        item.setUpdateTime(lib.getUpdatedAt() == null ? null : lib.getUpdatedAt().format(TIME));
        item.setShared(sharedIds.contains(lib.getId()) ? 1 : 0);
        item.setPermissions(permissionsOf(lib, operator));
        return item;
    }

    private Map<String, String> catalogNames(Set<String> catalogIds) {
        Map<String, String> names = new HashMap<>();
        List<String> queryIds = new ArrayList<>();
        for (String id : catalogIds) {
            if (id != null && !"root".equals(id)) {
                queryIds.add(id);
            }
        }
        if (!queryIds.isEmpty()) {
            for (ExamCatalog c : catalogMapper.selectBatchIds(queryIds)) {
                names.put(c.getId(), c.getName());
            }
        }
        return names;
    }

    /** 用户姓名（经 Dubbo；失败回空串）。 */
    private String fullnameOf(String userId) {
        try {
            List<SysUserDTO> users = orgQueryService.listUsersByIds(List.of(Long.parseLong(userId)));
            return users.isEmpty() ? "" : users.get(0).getName();
        } catch (Exception e) {
            return "";
        }
    }
}

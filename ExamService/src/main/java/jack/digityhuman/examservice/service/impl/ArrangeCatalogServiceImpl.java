package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ExamCatalogDtos;
import jack.digityhuman.examservice.dto.resp.ExamCatalogRespDtos;
import jack.digityhuman.examservice.entity.ExamArrangeCatalog;
import jack.digityhuman.examservice.entity.ExamArrangeCatalogAdmin;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.mapper.ExamArrangeCatalogAdminMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeCatalogMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.service.ArrangeCatalogService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类树服务实现（FP-03）。
 *
 * <p>routing_path 维护（02 §4.2）：新建取同级 max+1 三位序号拼父 path；移动整棵子树前缀替换
 * （逐层 REPLACE，事务内）；查询按 catalog_id OR routing_path LIKE 前缀%（含子分类）。
 * 权限继承（FP-05 一部分）在读取时合并：勾继承一级分类权限 → 管理员列表读父级（一级）的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArrangeCatalogServiceImpl implements ArrangeCatalogService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 分类体系最多 10 级（01 FP-03 填写须知） */
    private static final int MAX_LEVEL = 10;
    /** 一次最多批量创建 50 个分类 */
    private static final int MAX_BATCH = 50;

    private final ExamArrangeCatalogMapper catalogMapper;
    private final ExamArrangeCatalogAdminMapper catalogAdminMapper;
    private final ExamArrangeMapper arrangeMapper;
    private final OrgQueryService orgQueryService;
    private final CurrentUserService currentUserService;

    @Override
    public ExamCatalogRespDtos.TreeNode tree() {
        List<ExamArrangeCatalog> all = catalogMapper.selectList(
                Wrappers.<ExamArrangeCatalog>lambdaQuery().orderByAsc(ExamArrangeCatalog::getRoutingPath));
        Map<String, List<ExamArrangeCatalog>> byParent = new HashMap<>();
        for (ExamArrangeCatalog c : all) {
            // 根节点 parent_id='root' 自指——跳过自身，否则 toNode 递归时 root 会成为自己的 child（StackOverflow）
            if (ExamArrangeCatalog.ROOT_ID.equals(c.getId()) && ExamArrangeCatalog.ROOT_ID.equals(c.getParentId())) {
                continue;
            }
            byParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
        }
        ExamArrangeCatalog root = all.stream()
                .filter(c -> ExamArrangeCatalog.ROOT_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new BizException("分类根节点缺失，请检查种子数据"));
        ExamCatalogRespDtos.TreeNode rootNode = toNode(root, byParent);
        // 展平统计（libQty/childCatalogQty 含子孙）
        countSubtree(rootNode);
        return rootNode;
    }

    @Override
    public ExamCatalogRespDtos.CatalogDetail detail(String id) {
        ExamArrangeCatalog catalog = requireCatalog(id);
        ExamCatalogRespDtos.CatalogDetail d = new ExamCatalogRespDtos.CatalogDetail();
        d.setId(catalog.getId());
        d.setName(catalog.getName());
        d.setParentName(ExamArrangeCatalog.ROOT_ID.equals(catalog.getParentId())
                ? "默认题库分类" : nameOf(catalog.getParentId()));
        d.setLibQty((int) countDirectArranges(id));
        d.setCreateTime(catalog.getCreatedAt() == null ? null : catalog.getCreatedAt().format(TIME));
        d.setCreateFullname(catalog.getCreatedByName());
        d.setInheritParentAuth(catalog.getInheritParentAuth());
        d.setChildInheritCurrentAuth(catalog.getChildInheritCurrentAuth());
        d.setUsers(adminUsersOf(effectiveCatalogId(catalog)));
        return d;
    }

    @Override
    @Transactional
    public ExamCatalogDtos.BatchCreateResult createBatch(ExamCatalogDtos.BatchCreateReq req) {
        String parentId = req.getParentId() == null || req.getParentId().isBlank()
                ? ExamArrangeCatalog.ROOT_ID : req.getParentId();
        ExamArrangeCatalog parent = requireCatalog(parentId);
        if (parent.getDisabled() != null && parent.getDisabled() == 1) {
            throw new BizException("所属分类已禁用，无法在其下新建");
        }
        String text = req.getCatalogText() == null ? "" : req.getCatalogText();
        String[] lines = text.split("\n", -1);
        List<ExamCatalogDtos.BatchCreateResult.LineError> errors = new ArrayList<>();
        List<ExamArrangeCatalog> toCreate = new ArrayList<>();
        // 行解析：缩进空格数推层级；parentStack 维护"当前层级的最近父"
        List<String> parentStack = new ArrayList<>(List.of(parentId));
        List<Integer> parentIndent = new ArrayList<>(List.of(-1));
        int created = 0;
        int dataLines = 0;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            dataLines++;
            if (dataLines > MAX_BATCH) {
                errors.add(new ExamCatalogDtos.BatchCreateResult.LineError(i + 1, "一次最多批量创建50个分类"));
                break;
            }
            int indent = raw.indexOf(trimmed); // 前导空格数（Tab 按 1 空格口径 strip 前后差）
            if (trimmed.length() > 200) {
                errors.add(new ExamCatalogDtos.BatchCreateResult.LineError(i + 1, "分类名称200字以内"));
                continue;
            }
            // 层级定位：缩进回退到 ≤ 自身的最大已见层级
            while (parentIndent.size() > 1 && indent <= parentIndent.get(parentIndent.size() - 1)) {
                parentStack.remove(parentStack.size() - 1);
                parentIndent.remove(parentIndent.size() - 1);
            }
            int level = parentStack.size(); // 相对父分类的层级（父=1）
            String currentParent = parentStack.get(parentStack.size() - 1);
            // 绝对层级校验（root 子级算第 1 级，共 10 级）
            int absLevel = level + depthOf(currentParent);
            if (absLevel > MAX_LEVEL) {
                errors.add(new ExamCatalogDtos.BatchCreateResult.LineError(i + 1, "分类体系最多支持10级"));
                continue;
            }
            ExamArrangeCatalog c = new ExamArrangeCatalog();
            c.setParentId(currentParent);
            c.setName(trimmed);
            c.setOrderIndex(0);
            c.setRoutingPath("");
            c.setInheritParentAuth(req.getInheritParentAuth() == null ? 0 : req.getInheritParentAuth());
            c.setChildInheritCurrentAuth(req.getChildInheritCurrentAuth() == null ? 0 : req.getChildInheritCurrentAuth());
            c.setDisabled(0);
            c.setDeleted(0);
            c.setCreatedBy(currentUserService.userId(null));
            c.setCreatedAt(LocalDateTime.now());
            toCreate.add(c);
            parentStack.add("PENDING:" + (toCreate.size() - 1)); // 占位=本批父在 toCreate 的下标
            parentIndent.add(indent);
        }
        // 逐个落库（routing_path 需要父 id + 同级 max，逐个算）
        for (int i = 0; i < toCreate.size(); i++) {
            ExamArrangeCatalog c = toCreate.get(i);
            String pid = c.getParentId();
            if (pid != null && pid.startsWith("PENDING:")) {
                // 本批内的父：占位即 toCreate 下标；insert 已回填其雪花 id
                int idx = Integer.parseInt(pid.substring("PENDING:".length()));
                pid = toCreate.get(idx).getId();
                c.setParentId(pid);
            }
            c.setRoutingPath(nextRoutingPath(pid));
            c.setOrderIndex(nextOrderIndex(pid));
            catalogMapper.insert(c);
            created++;
        }
        // 管理员批量挂到本次新建的所有分类（对齐原平台语义：抽屉管理员应用于整批）
        if (req.getAdminUserIds() != null && !req.getAdminUserIds().isEmpty() && !toCreate.isEmpty()) {
            for (ExamArrangeCatalog c : toCreate) {
                saveAdmins(c.getId(), req.getAdminUserIds());
            }
        }
        ExamCatalogDtos.BatchCreateResult result = new ExamCatalogDtos.BatchCreateResult();
        result.setCreatedCount(created);
        result.setErrors(errors);
        return result;
    }

    @Override
    @Transactional
    public void update(String id, ExamCatalogDtos.UpdateReq req) {
        ExamArrangeCatalog catalog = requireCatalog(id);
        if (req.getName() != null && !req.getName().isBlank()) {
            if (req.getName().length() > 200) {
                throw new BizException("分类名称200字以内");
            }
            catalog.setName(req.getName().trim());
        }
        if (req.getInheritParentAuth() != null) {
            catalog.setInheritParentAuth(req.getInheritParentAuth());
        }
        if (req.getChildInheritCurrentAuth() != null) {
            catalog.setChildInheritCurrentAuth(req.getChildInheritCurrentAuth());
        }
        catalog.setUpdatedBy(currentUserService.userId(null));
        catalog.setUpdatedAt(LocalDateTime.now());
        catalogMapper.updateById(catalog);
        // 管理员全量覆盖（继承态下前端禁改，后端照存不损失）
        if (req.getAdminUserIds() != null) {
            catalogAdminMapper.delete(Wrappers.<ExamArrangeCatalogAdmin>lambdaQuery()
                    .eq(ExamArrangeCatalogAdmin::getCatalogId, id));
            saveAdmins(id, req.getAdminUserIds());
        }
    }

    @Override
    @Transactional
    public void move(String id, String targetParentId) {
        ExamArrangeCatalog catalog = requireCatalog(id);
        String target = targetParentId == null || targetParentId.isBlank()
                ? ExamArrangeCatalog.ROOT_ID : targetParentId;
        if (target.equals(id)) {
            throw new BizException("不能移动到自身");
        }
        ExamArrangeCatalog targetCatalog = requireCatalog(target);
        // 目标不能是自身子孙（routing_path 前缀判断）
        if (!ExamArrangeCatalog.ROOT_ID.equals(target) && targetCatalog.getRoutingPath().startsWith(
                catalog.getRoutingPath() + ".") || targetCatalog.getRoutingPath().startsWith(catalog.getRoutingPath() + ".")) {
            throw new BizException("不能移动到自己的子分类下");
        }
        // 层级校验：目标层级 + 被移动子树深度 ≤ 10
        int targetLevel = targetCatalog.getRoutingPath().isEmpty() ? 0
                : targetCatalog.getRoutingPath().split("\\.").length;
        int subtreeDepth = maxSubtreeDepth(catalog);
        if (targetLevel + 1 + subtreeDepth > MAX_LEVEL) {
            throw new BizException("分类体系最多支持10级");
        }
        String oldPath = catalog.getRoutingPath();
        // 目标父下重编号
        catalog.setParentId(target);
        catalog.setRoutingPath(nextRoutingPath(target));
        catalog.setOrderIndex(nextOrderIndex(target));
        catalog.setUpdatedBy(currentUserService.userId(null));
        catalogMapper.updateById(catalog);
        // 整棵子树前缀替换（逐层：先子再孙，避免 path 重复替换——用 LIKE 限定下一层逐层推进）
        replaceSubtreePrefix(oldPath, catalog.getRoutingPath());
    }

    @Override
    public void disable(String id, int disabled) {
        ExamArrangeCatalog catalog = requireCatalog(id);
        catalog.setDisabled(disabled);
        catalog.setUpdatedBy(currentUserService.userId(null));
        catalogMapper.updateById(catalog);
    }

    @Override
    public void delete(String id) {
        if (ExamArrangeCatalog.ROOT_ID.equals(id)) {
            throw new BizException("根分类不可删除");
        }
        requireCatalog(id);
        long childCount = catalogMapper.selectCount(Wrappers.<ExamArrangeCatalog>lambdaQuery()
                .eq(ExamArrangeCatalog::getParentId, id));
        long libCount = arrangeMapper.selectCount(Wrappers.<ExamArrange>lambdaQuery()
                .eq(ExamArrange::getCatalogId, id));
        if (childCount > 0 || libCount > 0) {
            throw new BizException(40001, "分类下存在子分类或题库，无法删除");
        }
        catalogMapper.deleteById(id);
        catalogAdminMapper.delete(Wrappers.<ExamArrangeCatalogAdmin>lambdaQuery()
                .eq(ExamArrangeCatalogAdmin::getCatalogId, id));
    }

    // ---- 内部工具 ----

    private ExamArrangeCatalog requireCatalog(String id) {
        ExamArrangeCatalog catalog = catalogMapper.selectById(id);
        if (catalog == null) {
            throw new BizException("分类不存在或已删除");
        }
        return catalog;
    }

    /** 新分类 routing_path：父 path + 三位序号（同级 max+1）。 */
    private String nextRoutingPath(String parentId) {
        int next = nextOrderIndex(parentId);
        String seq = String.format("%03d", next);
        if (ExamArrangeCatalog.ROOT_ID.equals(parentId)) {
            return seq;
        }
        ExamArrangeCatalog parent = requireCatalog(parentId);
        return parent.getRoutingPath() + "." + seq;
    }

    /** 同级下一个排序号（1 起）。 */
    private int nextOrderIndex(String parentId) {
        List<ExamArrangeCatalog> siblings = catalogMapper.selectList(Wrappers.<ExamArrangeCatalog>lambdaQuery()
                .eq(ExamArrangeCatalog::getParentId, parentId));
        return siblings.stream()
                .map(c -> c.getOrderIndex() == null ? 0 : c.getOrderIndex())
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    /** 整棵子树 routing_path 前缀替换：按层级从浅到深逐层 REPLACE（父先改，子 LIKE 旧前缀.） */
    private void replaceSubtreePrefix(String oldPath, String newPath) {
        List<ExamArrangeCatalog> descendants = catalogMapper.selectList(Wrappers.<ExamArrangeCatalog>lambdaQuery()
                .likeRight(ExamArrangeCatalog::getRoutingPath, oldPath + "."));
        descendants.sort((a, b) -> Integer.compare(
                a.getRoutingPath().split("\\.").length, b.getRoutingPath().split("\\.").length));
        for (ExamArrangeCatalog d : descendants) {
            String updated = newPath + d.getRoutingPath().substring(oldPath.length());
            d.setRoutingPath(updated);
            catalogMapper.updateById(d);
        }
    }

    /** 被移动子树的最大相对深度（自身=1）。 */
    private int maxSubtreeDepth(ExamArrangeCatalog catalog) {
        List<ExamArrangeCatalog> descendants = catalogMapper.selectList(Wrappers.<ExamArrangeCatalog>lambdaQuery()
                .likeRight(ExamArrangeCatalog::getRoutingPath, catalog.getRoutingPath() + "."));
        int maxLen = catalog.getRoutingPath().split("\\.").length;
        for (ExamArrangeCatalog d : descendants) {
            maxLen = Math.max(maxLen, d.getRoutingPath().split("\\.").length);
        }
        return maxLen - catalog.getRoutingPath().split("\\.").length + 1;
    }

    /** 分类绝对层级（root 子级=1）。 */
    private int depthOf(String catalogId) {
        if (ExamArrangeCatalog.ROOT_ID.equals(catalogId)) {
            return 0;
        }
        ExamArrangeCatalog c = catalogMapper.selectById(catalogId);
        return c == null || c.getRoutingPath().isEmpty() ? 1 : c.getRoutingPath().split("\\.").length;
    }

    private String nameOf(String catalogId) {
        ExamArrangeCatalog c = catalogMapper.selectById(catalogId);
        return c == null ? "" : c.getName();
    }

    private long countDirectArranges(String catalogId) {
        return arrangeMapper.selectCount(Wrappers.<ExamArrange>lambdaQuery()
                .eq(ExamArrange::getCatalogId, catalogId));
    }

    /** 权限继承合并（FP-05）：勾"继承一级分类权限"→ 管理员读一级分类（routing_path 首段）的。 */
    private String effectiveCatalogId(ExamArrangeCatalog catalog) {
        if (catalog.getInheritParentAuth() != null && catalog.getInheritParentAuth() == 1
                && !ExamArrangeCatalog.ROOT_ID.equals(catalog.getParentId())) {
            String[] parts = catalog.getRoutingPath().split("\\.");
            if (parts.length > 0) {
                String firstLevelPath = parts[0];
                ExamArrangeCatalog first = catalogMapper.selectList(Wrappers.<ExamArrangeCatalog>lambdaQuery()
                                .eq(ExamArrangeCatalog::getRoutingPath, firstLevelPath))
                        .stream().findFirst().orElse(null);
                if (first != null) {
                    return first.getId();
                }
            }
        }
        return catalog.getId();
    }

    /** 该分类的管理员列表（含姓名/部门冗余，经 Dubbo 批量补齐）。 */
    private List<ExamCatalogRespDtos.AdminUser> adminUsersOf(String catalogId) {
        List<ExamArrangeCatalogAdmin> rels = catalogAdminMapper.selectList(Wrappers.<ExamArrangeCatalogAdmin>lambdaQuery()
                .eq(ExamArrangeCatalogAdmin::getCatalogId, catalogId));
        List<ExamCatalogRespDtos.AdminUser> users = new ArrayList<>();
        for (ExamArrangeCatalogAdmin rel : rels) {
            ExamCatalogRespDtos.AdminUser u = new ExamCatalogRespDtos.AdminUser();
            u.setUserId(rel.getUserId());
            users.add(u);
        }
        fillUserNames(users);
        return users;
    }

    /** 经 OrgQueryService 批量补姓名/工号/部门（查询失败静默降级为只回 userId）。
     *  契约：listUsersByIds(List&lt;Long&gt;) → List&lt;SysUserDTO&gt;（id/account/employeeNo/name/deptName）。 */
    private void fillUserNames(List<ExamCatalogRespDtos.AdminUser> users) {
        if (users.isEmpty()) {
            return;
        }
        try {
            List<Long> ids = new ArrayList<>();
            for (ExamCatalogRespDtos.AdminUser u : users) {
                try {
                    ids.add(Long.parseLong(u.getUserId()));
                } catch (NumberFormatException ignored) {
                    // userId 非数字（如 "0" 系统占位）：跳过补齐
                }
            }
            if (ids.isEmpty()) {
                return;
            }
            List<jack.digityhuman.staffmanagement.api.dto.SysUserDTO> dtos =
                    orgQueryService.listUsersByIds(ids);
            Map<String, jack.digityhuman.staffmanagement.api.dto.SysUserDTO> byId = new HashMap<>();
            for (var dto : dtos) {
                byId.put(String.valueOf(dto.getId()), dto);
            }
            for (ExamCatalogRespDtos.AdminUser u : users) {
                var dto = byId.get(u.getUserId());
                if (dto != null) {
                    u.setFullname(dto.getName());
                    u.setUserName(dto.getEmployeeNo() != null ? dto.getEmployeeNo() : dto.getAccount());
                    u.setDeptName(dto.getDeptName());
                }
            }
        } catch (Exception e) {
            log.warn("补齐分类管理员姓名失败（降级只回 userId）: {}", e.getMessage());
        }
    }

    private void saveAdmins(String catalogId, List<String> userIds) {
        for (String userId : userIds) {
            if (userId == null || userId.isBlank()) {
                continue;
            }
            Long exists = catalogAdminMapper.selectCount(Wrappers.<ExamArrangeCatalogAdmin>lambdaQuery()
                    .eq(ExamArrangeCatalogAdmin::getCatalogId, catalogId)
                    .eq(ExamArrangeCatalogAdmin::getUserId, userId));
            if (exists == 0) {
                ExamArrangeCatalogAdmin rel = new ExamArrangeCatalogAdmin();
                rel.setCatalogId(catalogId);
                rel.setUserId(userId);
                catalogAdminMapper.insert(rel);
            }
        }
    }

    /** 实体 → 树节点（children 递归）。 */
    private ExamCatalogRespDtos.TreeNode toNode(ExamArrangeCatalog c, Map<String, List<ExamArrangeCatalog>> byParent) {
        ExamCatalogRespDtos.TreeNode node = new ExamCatalogRespDtos.TreeNode();
        node.setId(c.getId());
        node.setName(c.getName());
        node.setParentId(c.getParentId());
        node.setRoutingPath(c.getRoutingPath());
        node.setOrderIndex(c.getOrderIndex());
        node.setDisabled(c.getDisabled());
        node.setInheritParentAuth(c.getInheritParentAuth());
        node.setChildInheritCurrentAuth(c.getChildInheritCurrentAuth());
        node.setUsers(adminUsersOf(effectiveCatalogId(c)));
        List<ExamArrangeCatalog> children = byParent.getOrDefault(c.getId(), List.of());
        if (!children.isEmpty()) {
            List<ExamCatalogRespDtos.TreeNode> childNodes = new ArrayList<>();
            for (ExamArrangeCatalog child : children) {
                childNodes.add(toNode(child, byParent));
            }
            node.setChildren(childNodes);
        }
        return node;
    }

    /** 子树统计回填：libQty/childCatalogQty 含全部子孙（对齐原平台树展示口径）。 */
    private void countSubtree(ExamCatalogRespDtos.TreeNode node) {
        if (node.getChildren() == null) {
            node.setLibQty((int) countDirectArranges(node.getId()));
            node.setChildCatalogQty(0);
            return;
        }
        int libTotal = (int) countDirectArranges(node.getId());
        int childTotal = node.getChildren().size();
        for (ExamCatalogRespDtos.TreeNode child : node.getChildren()) {
            countSubtree(child);
            libTotal += child.getLibQty() == null ? 0 : child.getLibQty();
            childTotal += child.getChildCatalogQty() == null ? 0 : child.getChildCatalogQty();
        }
        node.setLibQty(libTotal);
        node.setChildCatalogQty(childTotal);
    }
}

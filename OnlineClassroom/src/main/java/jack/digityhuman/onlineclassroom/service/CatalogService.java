package jack.digityhuman.onlineclassroom.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.onlineclassroom.common.BizException;
import jack.digityhuman.onlineclassroom.dto.KngCatalogDtos;
import jack.digityhuman.onlineclassroom.entity.KngCatalog;
import jack.digityhuman.onlineclassroom.entity.KngCatalogPermission;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在线课堂分类树、权限矩阵、继承、批量创建与移动的核心服务。
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LEVEL = 10;

    private final KngCatalogMapper catalogMapper;
    private final KngCatalogPermissionMapper permissionMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 分类整树；展开/搜索/统计口径切换全部由前端在本地完成。 */
    public List<KngCatalogDtos.TreeNode> tree() {
        List<KngCatalog> catalogs = allCatalogs();
        Map<String, KngCatalogDtos.TreeNode> nodes = new LinkedHashMap<>();
        for (KngCatalog catalog : catalogs) {
            nodes.put(catalog.getId(), toTreeNode(catalog));
        }
        List<KngCatalogDtos.TreeNode> roots = new ArrayList<>();
        for (KngCatalog catalog : catalogs) {
            KngCatalogDtos.TreeNode node = nodes.get(catalog.getId());
            KngCatalogDtos.TreeNode parent = nodes.get(catalog.getParentId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        roots.forEach(this::aggregate);
        return roots;
    }

    /** 查看/编辑抽屉共用详情。 */
    public KngCatalogDtos.Detail detail(String id) {
        KngCatalog catalog = requireCatalog(id);
        Map<String, KngCatalog> byId = allCatalogs().stream()
                .collect(Collectors.toMap(KngCatalog::getId, item -> item));

        KngCatalogDtos.Detail detail = new KngCatalogDtos.Detail();
        detail.setId(catalog.getId());
        detail.setName(catalog.getName());
        detail.setParentId(catalog.getParentId());
        detail.setParentPath(parentPath(catalog, byId));
        detail.setLevel(catalog.getLevel());
        detail.setOrderType(catalog.getOrderType());
        detail.setPrefixEnabled(isTrue(catalog.getPrefixEnabled()));
        detail.setPrefixContent(catalog.getPrefixContent());
        detail.setDateSuffixEnabled(isTrue(catalog.getDateSuffixEnabled()));
        detail.setDateSuffixFormat(catalog.getDateSuffixFormat());
        detail.setInheritParent(isTrue(catalog.getInheritParent()));
        detail.setCreateTime(catalog.getCreatedAt() == null ? null : DATE_TIME.format(catalog.getCreatedAt()));
        detail.setCreateUser(findUser(catalog.getCreatedBy()));
        detail.setPermissions(permissionItems(catalog.getId()));
        return detail;
    }

    @Transactional
    public KngCatalogDtos.IdResponse create(KngCatalogDtos.SaveRequest request, String userId) {
        ValidatedSave save = validateSave(request, null, true);
        KngCatalog catalog = new KngCatalog();
        catalog.setId(IdWorker.getIdStr());
        catalog.setParentId(save.parentId());
        catalog.setName(save.name());
        catalog.setLevel(save.level());
        catalog.setOrderIndex(nextOrderIndex(save.parentId()));
        catalog.setOrderType(save.orderType());
        catalog.setPrefixEnabled(save.prefixEnabled() ? 1 : 0);
        catalog.setPrefixContent(save.prefixEnabled() ? save.prefixContent() : null);
        catalog.setDateSuffixEnabled(save.dateSuffixEnabled() ? 1 : 0);
        catalog.setDateSuffixFormat(save.dateSuffixEnabled() ? save.dateSuffixFormat() : null);
        catalog.setInheritParent(Boolean.TRUE.equals(request.inheritParent()) ? 1 : 0);
        catalog.setKngCount(0);
        catalog.setCourseCount(0);
        catalog.setCreatedBy(userId);
        catalog.setCreatedAt(LocalDateTime.now());
        catalog.setUpdatedAt(LocalDateTime.now());
        catalogMapper.insert(catalog);

        List<KngCatalogDtos.PermissionItem> permissions = Boolean.TRUE.equals(request.inheritParent())
                ? permissionItems(save.parentId())
                : normalizePermissions(request.permissions(), true);
        if (permissions.isEmpty()) {
            permissions = defaultPermissions();
        }
        replacePermissions(catalog.getId(), permissions);
        return new KngCatalogDtos.IdResponse(catalog.getId());
    }

    @Transactional
    public KngCatalogDtos.IdResponse update(String id, KngCatalogDtos.SaveRequest request, String userId) {
        KngCatalog catalog = requireCatalog(id);
        ValidatedSave save = validateSave(request, id, false);
        catalog.setName(save.name());
        catalog.setOrderType(save.orderType());
        catalog.setPrefixEnabled(save.prefixEnabled() ? 1 : 0);
        catalog.setPrefixContent(save.prefixEnabled() ? save.prefixContent() : null);
        catalog.setDateSuffixEnabled(save.dateSuffixEnabled() ? 1 : 0);
        catalog.setDateSuffixFormat(save.dateSuffixEnabled() ? save.dateSuffixFormat() : null);
        catalog.setInheritParent(Boolean.TRUE.equals(request.inheritParent()) ? 1 : 0);
        catalog.setUpdatedAt(LocalDateTime.now());
        catalogMapper.updateById(catalog);

        List<KngCatalogDtos.PermissionItem> effectivePermissions;
        if (Boolean.TRUE.equals(request.inheritParent())) {
            if (!StringUtils.hasText(catalog.getParentId())) {
                throw new BizException("一级分类没有父分类权限可继承");
            }
            effectivePermissions = permissionItems(catalog.getParentId());
        } else {
            effectivePermissions = normalizePermissions(request.permissions(), false);
        }
        replacePermissions(id, effectivePermissions);

        if (Boolean.TRUE.equals(request.childrenInherit())) {
            for (KngCatalog descendant : descendants(id)) {
                replacePermissions(descendant.getId(), effectivePermissions);
            }
        }
        return new KngCatalogDtos.IdResponse(id);
    }

    @Transactional
    public void delete(String id) {
        requireCatalog(id);
        Long children = catalogMapper.selectCount(
                Wrappers.<KngCatalog>lambdaQuery().eq(KngCatalog::getParentId, id));
        if (children != null && children > 0) {
            throw new BizException("该分类下存在子分类，请先处理子分类");
        }
        permissionMapper.delete(Wrappers.<KngCatalogPermission>lambdaQuery()
                .eq(KngCatalogPermission::getCatalogId, id));
        catalogMapper.deleteById(id);
    }

    /**
     * 解析缩进文本批量创建；任一行失败时事务整体回滚。
     */
    @Transactional
    public KngCatalogDtos.CountResponse batchCreate(KngCatalogDtos.BatchRequest request, String userId) {
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BizException("请填写分类内容");
        }
        KngCatalog baseParent = StringUtils.hasText(request.parentId())
                ? requireCatalog(request.parentId()) : null;
        int baseLevel = baseParent == null ? 0 : baseParent.getLevel();
        String rootParentId = baseParent == null ? null : baseParent.getId();

        List<KngCatalog> existing = allCatalogs();
        Map<String, Set<String>> siblingNames = new HashMap<>();
        Map<String, Integer> nextOrder = new HashMap<>();
        for (KngCatalog catalog : existing) {
            siblingNames.computeIfAbsent(parentKey(catalog.getParentId()), ignored -> new HashSet<>())
                    .add(catalog.getName());
            nextOrder.merge(parentKey(catalog.getParentId()),
                    catalog.getOrderIndex() == null ? 0 : catalog.getOrderIndex() + 1, Math::max);
        }

        String[] rawLines = request.content().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Map<Integer, String> parentStack = new HashMap<>();
        int previousDepth = -1;
        int created = 0;

        for (int index = 0; index < rawLines.length; index++) {
            String line = rawLines[index];
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if (line.indexOf('\t') >= 0) {
                throw new BizException("第 " + (index + 1) + " 行请使用空格缩进");
            }
            int spaces = leadingSpaces(line);
            String name = line.trim();
            if (spaces > previousDepth + 1) {
                throw new BizException("第 " + (index + 1) + " 行缩进层级错误");
            }
            if (name.length() > 50) {
                throw new BizException("第 " + (index + 1) + " 行分类名称超出 50 字");
            }
            int level = baseLevel + spaces + 1;
            if (level > MAX_LEVEL) {
                throw new BizException("超出 10 级层级限制");
            }
            String parentId = spaces == 0 ? rootParentId : parentStack.get(spaces - 1);
            if (spaces > 0 && !StringUtils.hasText(parentId)) {
                throw new BizException("第 " + (index + 1) + " 行缩进层级错误");
            }
            Set<String> names = siblingNames.computeIfAbsent(parentKey(parentId), ignored -> new HashSet<>());
            if (!names.add(name)) {
                throw new BizException("第 " + (index + 1) + " 行同级已存在同名分类「" + name + "」");
            }

            KngCatalog catalog = new KngCatalog();
            catalog.setId(IdWorker.getIdStr());
            catalog.setParentId(parentId);
            catalog.setName(name);
            catalog.setLevel(level);
            catalog.setOrderIndex(nextOrder.getOrDefault(parentKey(parentId), 0));
            nextOrder.put(parentKey(parentId), catalog.getOrderIndex() + 1);
            catalog.setOrderType(1);
            catalog.setPrefixEnabled(0);
            catalog.setDateSuffixEnabled(0);
            catalog.setInheritParent(0);
            catalog.setKngCount(0);
            catalog.setCourseCount(0);
            catalog.setCreatedBy(userId);
            catalog.setCreatedAt(LocalDateTime.now());
            catalog.setUpdatedAt(LocalDateTime.now());
            catalogMapper.insert(catalog);

            List<KngCatalogDtos.PermissionItem> inherited = StringUtils.hasText(parentId)
                    ? permissionItems(parentId) : defaultPermissions();
            if (inherited.isEmpty()) {
                inherited = defaultPermissions();
            }
            replacePermissions(catalog.getId(), inherited);

            parentStack.put(spaces, catalog.getId());
            parentStack.keySet().removeIf(depth -> depth > spaces);
            previousDepth = spaces;
            created++;
        }
        if (created == 0) {
            throw new BizException("请填写分类内容");
        }
        return new KngCatalogDtos.CountResponse(created);
    }

    /** 移动整棵子树，递归更新子孙层级。 */
    @Transactional
    public KngCatalogDtos.CountResponse move(String id, KngCatalogDtos.MoveRequest request) {
        KngCatalog catalog = requireCatalog(id);
        String targetParentId = request == null ? null : request.targetParentId();
        KngCatalog targetParent = StringUtils.hasText(targetParentId)
                ? requireCatalog(targetParentId) : null;
        if (targetParent != null && (targetParent.getId().equals(id) || descendantIds(id).contains(targetParent.getId()))) {
            throw new BizException("不能移动到自身或其子分类下");
        }
        int newLevel = targetParent == null ? 1 : targetParent.getLevel() + 1;
        List<KngCatalog> subtree = subtree(id);
        int relativeDeepest = subtree.stream()
                .mapToInt(item -> item.getLevel() - catalog.getLevel())
                .max().orElse(0);
        if (newLevel + relativeDeepest > MAX_LEVEL) {
            throw new BizException("目标层级超出 10 级限制");
        }
        ensureSiblingNameUnique(targetParentId, catalog.getName(), id);

        int delta = newLevel - catalog.getLevel();
        catalog.setParentId(targetParentId);
        catalog.setLevel(newLevel);
        catalog.setOrderIndex(nextOrderIndex(targetParentId));
        catalog.setUpdatedAt(LocalDateTime.now());
        catalogMapper.updateById(catalog);
        for (KngCatalog item : subtree) {
            if (item.getId().equals(id)) {
                continue;
            }
            item.setLevel(item.getLevel() + delta);
            item.setUpdatedAt(LocalDateTime.now());
            catalogMapper.updateById(item);
        }
        return new KngCatalogDtos.CountResponse(subtree.size());
    }

    /** 当前无课程数据，接口预留并保持可调用。 */
    public KngCatalogDtos.CountResponse moveCourses(String id, KngCatalogDtos.MoveRequest request) {
        requireCatalog(id);
        if (request != null && StringUtils.hasText(request.targetParentId())) {
            requireCatalog(request.targetParentId());
        }
        return new KngCatalogDtos.CountResponse(0);
    }

    /** 合并当前分类权限到父分类，父分类同对象取权限并集。 */
    @Transactional
    public KngCatalogDtos.CountResponse mergeToParent(String id) {
        KngCatalog catalog = requireCatalog(id);
        if (!StringUtils.hasText(catalog.getParentId())) {
            throw new BizException("一级分类没有父分类可合并");
        }
        List<KngCatalogPermission> parentRows = rawPermissions(catalog.getParentId());
        Map<String, KngCatalogPermission> byKey = parentRows.stream()
                .collect(Collectors.toMap(this::permissionKey, item -> item, (left, right) -> left));
        int inserted = 0;
        for (KngCatalogPermission source : rawPermissions(id)) {
            KngCatalogPermission existing = byKey.get(permissionKey(source));
            if (existing == null) {
                KngCatalogPermission copy = copyPermission(source, catalog.getParentId());
                permissionMapper.insert(copy);
                inserted++;
            } else {
                Set<Integer> union = new HashSet<>(parseCodes(existing.getPermissions()));
                union.addAll(parseCodes(source.getPermissions()));
                existing.setPermissions(codesToCsv(union));
                permissionMapper.updateById(existing);
            }
        }
        return new KngCatalogDtos.CountResponse(inserted);
    }

    public List<KngCatalog> allCatalogs() {
        return catalogMapper.selectList(Wrappers.<KngCatalog>lambdaQuery()
                .orderByAsc(KngCatalog::getParentId)
                .orderByAsc(KngCatalog::getOrderIndex)
                .orderByAsc(KngCatalog::getId));
    }

    private KngCatalogDtos.TreeNode toTreeNode(KngCatalog catalog) {
        KngCatalogDtos.TreeNode node = new KngCatalogDtos.TreeNode();
        node.setId(catalog.getId());
        node.setName(catalog.getName());
        node.setLevel(catalog.getLevel());
        node.setOrderIndex(catalog.getOrderIndex());
        node.setKngCount(catalog.getKngCount());
        node.setCourseCount(catalog.getCourseCount());
        node.setUpdateTime(catalog.getUpdatedAt() == null ? null : DATE.format(catalog.getUpdatedAt()));
        return node;
    }

    private int[] aggregate(KngCatalogDtos.TreeNode node) {
        int kng = node.getKngCount() == null ? 0 : node.getKngCount();
        int course = node.getCourseCount() == null ? 0 : node.getCourseCount();
        for (KngCatalogDtos.TreeNode child : node.getChildren()) {
            int[] childCounts = aggregate(child);
            kng += childCounts[0];
            course += childCounts[1];
        }
        node.setAllKngCount(kng);
        node.setAllCourseCount(course);
        return new int[]{kng, course};
    }

    private ValidatedSave validateSave(KngCatalogDtos.SaveRequest request, String excludeId, boolean create) {
        if (request == null) {
            throw new BizException("请求参数不能为空");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BizException("请输入分类名称，建议6个字以内");
        }
        if (name.length() > 50) {
            throw new BizException("分类名称不能超过 50 字");
        }
        String parentId = StringUtils.hasText(request.parentId()) ? request.parentId() : null;
        KngCatalog parent = parentId == null ? null : requireCatalog(parentId);
        int level = parent == null ? 1 : parent.getLevel() + 1;
        if (level > MAX_LEVEL) {
            throw new BizException("超出 10 级层级限制");
        }
        ensureSiblingNameUnique(parentId, name, excludeId);
        int orderType = request.orderType() == null ? 1 : request.orderType();
        if (orderType < 1 || orderType > 4) {
            throw new BizException("默认课程顺序取值不正确");
        }
        boolean prefixEnabled = Boolean.TRUE.equals(request.prefixEnabled());
        String prefixContent = request.prefixContent() == null ? "" : request.prefixContent().trim();
        if (prefixEnabled && prefixContent.isEmpty()) {
            throw new BizException("请填写前缀内容");
        }
        if (prefixContent.length() > 50) {
            throw new BizException("前缀内容不能超过 50 字");
        }
        boolean dateEnabled = Boolean.TRUE.equals(request.dateSuffixEnabled());
        String dateFormat = request.dateSuffixFormat();
        if (dateEnabled && !Set.of("yyyy-MM-dd", "yyyy/MM/dd", "yyyyMMdd").contains(dateFormat)) {
            throw new BizException("请选择日期后缀格式");
        }
        return new ValidatedSave(parentId, name, level, orderType, prefixEnabled,
                prefixContent, dateEnabled, dateFormat);
    }

    private void ensureSiblingNameUnique(String parentId, String name, String excludeId) {
        var query = Wrappers.<KngCatalog>lambdaQuery().eq(KngCatalog::getName, name);
        if (StringUtils.hasText(parentId)) {
            query.eq(KngCatalog::getParentId, parentId);
        } else {
            query.isNull(KngCatalog::getParentId);
        }
        if (StringUtils.hasText(excludeId)) {
            query.ne(KngCatalog::getId, excludeId);
        }
        if (catalogMapper.selectCount(query) > 0) {
            throw new BizException("同级已存在同名分类「" + name + "」");
        }
    }

    private int nextOrderIndex(String parentId) {
        List<KngCatalog> siblings = StringUtils.hasText(parentId)
                ? catalogMapper.selectList(Wrappers.<KngCatalog>lambdaQuery().eq(KngCatalog::getParentId, parentId))
                : catalogMapper.selectList(Wrappers.<KngCatalog>lambdaQuery().isNull(KngCatalog::getParentId));
        return siblings.stream().map(KngCatalog::getOrderIndex)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(-1) + 1;
    }

    private KngCatalog requireCatalog(String id) {
        KngCatalog catalog = StringUtils.hasText(id) ? catalogMapper.selectById(id) : null;
        if (catalog == null) {
            throw new BizException("分类不存在");
        }
        return catalog;
    }

    private List<KngCatalog> subtree(String id) {
        Map<String, List<KngCatalog>> children = allCatalogs().stream()
                .filter(item -> item.getParentId() != null)
                .collect(Collectors.groupingBy(KngCatalog::getParentId));
        List<KngCatalog> result = new ArrayList<>();
        collectSubtree(id, children, result);
        return result;
    }

    private void collectSubtree(String id, Map<String, List<KngCatalog>> children, List<KngCatalog> result) {
        KngCatalog current = requireCatalog(id);
        result.add(current);
        for (KngCatalog child : children.getOrDefault(id, List.of())) {
            collectSubtree(child.getId(), children, result);
        }
    }

    private Set<String> descendantIds(String id) {
        return subtree(id).stream().map(KngCatalog::getId).filter(item -> !item.equals(id))
                .collect(Collectors.toSet());
    }

    private List<KngCatalog> descendants(String id) {
        return subtree(id).stream().filter(item -> !item.getId().equals(id)).toList();
    }

    private String parentPath(KngCatalog catalog, Map<String, KngCatalog> byId) {
        List<String> names = new ArrayList<>();
        KngCatalog current = catalog;
        while (StringUtils.hasText(current.getParentId())) {
            current = byId.get(current.getParentId());
            if (current == null) {
                break;
            }
            names.add(0, current.getName());
        }
        names.add(0, "主课堂");
        return String.join("/", names);
    }

    private KngCatalogDtos.UserBrief findUser(String userId) {
        KngCatalogDtos.UserBrief brief = new KngCatalogDtos.UserBrief();
        if (!StringUtils.hasText(userId) || "0".equals(userId)) {
            brief.setName("系统");
            brief.setAccount("--");
            return brief;
        }
        List<KngCatalogDtos.UserBrief> users = jdbcTemplate.query(
                "select name, account from sys_user where id = ?",
                (rs, rowNum) -> {
                    KngCatalogDtos.UserBrief item = new KngCatalogDtos.UserBrief();
                    item.setName(rs.getString("name"));
                    item.setAccount(rs.getString("account"));
                    return item;
                }, userId);
        if (users.isEmpty()) {
            brief.setName("系统");
            brief.setAccount("--");
            return brief;
        }
        return users.get(0);
    }

    private List<KngCatalogDtos.PermissionItem> permissionItems(String catalogId) {
        return rawPermissions(catalogId).stream().map(row -> {
            KngCatalogDtos.PermissionItem item = new KngCatalogDtos.PermissionItem();
            item.setSubjectType(row.getSubjectType());
            item.setSubjectId(row.getSubjectId());
            item.setSubjectName(row.getSubjectName());
            item.setPermCodes(parseCodes(row.getPermissions()));
            return item;
        }).toList();
    }

    private List<KngCatalogPermission> rawPermissions(String catalogId) {
        if (!StringUtils.hasText(catalogId)) {
            return List.of();
        }
        return permissionMapper.selectList(Wrappers.<KngCatalogPermission>lambdaQuery()
                .eq(KngCatalogPermission::getCatalogId, catalogId)
                .orderByAsc(KngCatalogPermission::getSubjectType)
                .orderByAsc(KngCatalogPermission::getCreatedAt)
                .orderByAsc(KngCatalogPermission::getId));
    }

    private void replacePermissions(String catalogId, List<KngCatalogDtos.PermissionItem> items) {
        permissionMapper.delete(Wrappers.<KngCatalogPermission>lambdaQuery()
                .eq(KngCatalogPermission::getCatalogId, catalogId));
        for (KngCatalogDtos.PermissionItem item : items) {
            KngCatalogPermission row = new KngCatalogPermission();
            row.setId(IdWorker.getIdStr());
            row.setCatalogId(catalogId);
            row.setSubjectType(item.getSubjectType());
            row.setSubjectId(item.getSubjectId());
            row.setSubjectName(item.getSubjectName());
            row.setPermissions(codesToCsv(new HashSet<>(item.getPermCodes())));
            row.setCreatedAt(LocalDateTime.now());
            permissionMapper.insert(row);
        }
    }

    private List<KngCatalogDtos.PermissionItem> normalizePermissions(
            List<KngCatalogDtos.PermissionItem> raw, boolean fillDefaults) {
        LinkedHashMap<String, KngCatalogDtos.PermissionItem> byKey = new LinkedHashMap<>();
        if (fillDefaults) {
            for (KngCatalogDtos.PermissionItem item : defaultPermissions()) {
                byKey.put(permissionKey(item), item);
            }
        }
        if (raw != null) {
            for (KngCatalogDtos.PermissionItem item : raw) {
                if (item == null || item.getSubjectType() == null
                        || item.getSubjectType() < 1 || item.getSubjectType() > 6) {
                    throw new BizException("授权对象类型不正确");
                }
                if (item.getSubjectType() >= 4 && !StringUtils.hasText(item.getSubjectId())) {
                    throw new BizException("自定义授权对象缺少 id");
                }
                if (item.getSubjectType() <= 3) {
                    item.setSubjectId(null);
                    item.setSubjectName(defaultSubjectName(item.getSubjectType()));
                }
                if (!StringUtils.hasText(item.getSubjectName())) {
                    item.setSubjectName("未命名授权对象");
                }
                item.setPermCodes(new ArrayList<>(parseCodes(codesToCsv(
                        new HashSet<>(item.getPermCodes() == null ? List.of() : item.getPermCodes())))));
                byKey.put(permissionKey(item), item);
            }
        }
        if (!fillDefaults) {
            for (int type = 1; type <= 3; type++) {
                if (!byKey.containsKey(type + ":null")) {
                    throw new BizException("权限设置缺少内置授权对象");
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private List<KngCatalogDtos.PermissionItem> defaultPermissions() {
        return List.of(
                permission(1, null, "超级管理员&分类创建者", List.of(1, 2, 3, 4, 5)),
                permission(2, null, "课堂管理员", List.of(1, 2, 3, 4, 5)),
                permission(3, null, "课堂成员", List.of(1, 2, 5)));
    }

    private KngCatalogDtos.PermissionItem permission(
            int type, String id, String name, List<Integer> codes) {
        KngCatalogDtos.PermissionItem item = new KngCatalogDtos.PermissionItem();
        item.setSubjectType(type);
        item.setSubjectId(id);
        item.setSubjectName(name);
        item.setPermCodes(new ArrayList<>(codes));
        return item;
    }

    private String defaultSubjectName(int type) {
        return switch (type) {
            case 1 -> "超级管理员&分类创建者";
            case 2 -> "课堂管理员";
            case 3 -> "课堂成员";
            default -> "授权对象";
        };
    }

    private KngCatalogPermission copyPermission(KngCatalogPermission source, String catalogId) {
        KngCatalogPermission copy = new KngCatalogPermission();
        copy.setId(IdWorker.getIdStr());
        copy.setCatalogId(catalogId);
        copy.setSubjectType(source.getSubjectType());
        copy.setSubjectId(source.getSubjectId());
        copy.setSubjectName(source.getSubjectName());
        copy.setPermissions(source.getPermissions());
        copy.setCreatedAt(LocalDateTime.now());
        return copy;
    }

    private String permissionKey(KngCatalogPermission item) {
        return item.getSubjectType() + ":" + (item.getSubjectId() == null ? "null" : item.getSubjectId());
    }

    private String permissionKey(KngCatalogDtos.PermissionItem item) {
        return item.getSubjectType() + ":" + (item.getSubjectId() == null ? "null" : item.getSubjectId());
    }

    private List<Integer> parseCodes(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(Integer::parseInt)
                .filter(code -> code >= 1 && code <= 5)
                .distinct()
                .sorted()
                .toList();
    }

    private String codesToCsv(Set<Integer> codes) {
        return codes.stream().filter(code -> code != null && code >= 1 && code <= 5)
                .sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private boolean isTrue(Integer value) {
        return value != null && value == 1;
    }

    private int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private String parentKey(String parentId) {
        return parentId == null ? "__root__" : parentId;
    }

    private record ValidatedSave(
            String parentId,
            String name,
            int level,
            int orderType,
            boolean prefixEnabled,
            String prefixContent,
            boolean dateSuffixEnabled,
            String dateSuffixFormat) {
    }
}

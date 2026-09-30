package jack.digityhuman.staffmanagement.service.impl;

import jack.digityhuman.staffmanagement.api.dto.DepartmentNodeDTO;
import jack.digityhuman.staffmanagement.api.query.WorkStatus;
import jack.digityhuman.staffmanagement.dal.DepartmentStat;
import jack.digityhuman.staffmanagement.dal.StaffViewRepository;
import jack.digityhuman.staffmanagement.service.DepartmentDirectoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门目录缓存与层级树还原。
 *
 * <p>为什么要有这一层：视图每次查询都要 2~3 秒（DISTINCT + 8 个 LEFT JOIN，Oracle 侧成本，改不动），
 * 而组织目录只有约 1384 行、变化很慢。把目录放进 Redis 后，"定位部门 + 还原层级 + 各级人数"
 * 全部走缓存，一次查库的开销只剩员工明细分页。
 *
 * <p>用 Redis 而不是进程内 Map：多实例共享一份，不占应用堆内存，重启也不用重新预热。
 */
@Service
public class DepartmentDirectoryServiceImpl implements DepartmentDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentDirectoryService.class);

    /** 全路径分隔符，视图里 "组织全名称" 用 "_" 拼接各级部门名。实测部门名本身不含 "_"。 */
    private static final String SEPARATOR = "_";

    /** v1 写入的是"每部门每状态按行数计"的错误人数（人数恒为 0/1），改口径后整体换 key 让它自然过期。 */
    private static final String REDIS_KEY = "staff:dept:directory:v2";
    private static final Duration REDIS_TTL = Duration.ofMinutes(30);
    private static final String FIELD_SEPARATOR = "\t";

    private final StringRedisTemplate redisTemplate;
    private final StaffViewRepository repository;

    public DepartmentDirectoryServiceImpl(StringRedisTemplate redisTemplate, StaffViewRepository repository) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
    }

    /** 取全量部门目录：先读 Redis，未命中回源 Oracle 并写回。 */
    public List<DepartmentStat> loadDirectory() {
        String cached = readCache();
        if (cached != null) {
            List<DepartmentStat> parsed = parse(cached);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        List<DepartmentStat> loaded = repository.loadDepartmentDirectory();
        writeCache(loaded);
        return loaded;
    }

    /** 按组织编号定位部门。 */
    public DepartmentStat requireByCode(String code) {
        return loadDirectory().stream()
                .filter(s -> code.equals(s.code()))
                .findFirst()
                .orElse(null);
    }

    /** 按组织名称定位部门。重名很多（"客户服务组" 就有 21 个不同编号），所以返回列表。 */
    public List<DepartmentStat> findByName(String name) {
        return loadDirectory().stream()
                .filter(s -> name.equals(s.name()))
                .toList();
    }

    /**
     * 以 {@code root} 为根还原子树层级。
     *
     * <p>目录里只有"有员工挂名"的部门，中间层可能缺号；缺的层按全路径切分补成空节点，
     * 保证树的结构和真实组织一致，而不是把断档的下级直接挂到上层。
     */
    public DepartmentNodeDTO buildSubtree(DepartmentStat root, WorkStatus status) {
        String prefix = root.path() + SEPARATOR;
        Map<String, DepartmentNodeDTO> nodes = new LinkedHashMap<>();
        DepartmentNodeDTO rootNode = ensureNode(nodes, root.path(), root.path());
        rootNode.setDeptCode(root.code());
        rootNode.setDeptName(root.name());
        rootNode.setDirectEmployeeCount(root.count(status));

        for (DepartmentStat stat : loadDirectory()) {
            if (stat.path() == null || !stat.path().startsWith(prefix)) {
                continue;
            }
            DepartmentNodeDTO node = ensureNode(nodes, stat.path(), root.path());
            node.setDeptCode(stat.code());
            node.setDeptName(stat.name());
            node.setDirectEmployeeCount(stat.count(status));
        }

        sortChildren(rootNode);
        fillTotalCount(rootNode);
        return rootNode;
    }

    /** 子树内的部门节点总数（含根）。 */
    public int countDepartments(DepartmentNodeDTO node) {
        int total = 1;
        for (DepartmentNodeDTO child : node.getChildren()) {
            total += countDepartments(child);
        }
        return total;
    }

    /** 递归建节点：先入表再挂父，避免路径断档时死循环。 */
    private DepartmentNodeDTO ensureNode(Map<String, DepartmentNodeDTO> nodes, String path, String rootPath) {
        DepartmentNodeDTO existing = nodes.get(path);
        if (existing != null) {
            return existing;
        }
        DepartmentNodeDTO node = new DepartmentNodeDTO();
        node.setDeptPath(path);
        node.setDeptName(lastSegment(path));
        node.setLevel(path.split(SEPARATOR).length);
        nodes.put(path, node);
        if (!path.equals(rootPath)) {
            ensureNode(nodes, parentPath(path), rootPath).getChildren().add(node);
        }
        return node;
    }

    private static String parentPath(String path) {
        int index = path.lastIndexOf(SEPARATOR);
        return index < 0 ? path : path.substring(0, index);
    }

    private static String lastSegment(String path) {
        int index = path.lastIndexOf(SEPARATOR);
        return index < 0 ? path : path.substring(index + 1);
    }

    private static void sortChildren(DepartmentNodeDTO node) {
        node.getChildren().sort(Comparator.comparing(DepartmentNodeDTO::getDeptPath));
        node.getChildren().forEach(DepartmentDirectoryService::sortChildren);
    }

    /** 后序遍历回填：本级人数 + 所有下级人数。 */
    private static int fillTotalCount(DepartmentNodeDTO node) {
        int total = node.getDirectEmployeeCount();
        for (DepartmentNodeDTO child : node.getChildren()) {
            total += fillTotalCount(child);
        }
        node.setTotalEmployeeCount(total);
        node.setChildCount(node.getChildren().size());
        return total;
    }

    private String readCache() {
        try {
            return redisTemplate.opsForValue().get(REDIS_KEY);
        } catch (RuntimeException ex) {
            log.warn("读取部门目录缓存失败，本次回源 Oracle", ex);
            return null;
        }
    }

    private void writeCache(List<DepartmentStat> directory) {
        if (directory.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(directory.size() * 64);
        for (DepartmentStat stat : directory) {
            if (stat.path() == null) {
                continue;
            }
            sb.append(nullToEmpty(stat.code())).append(FIELD_SEPARATOR)
                    .append(nullToEmpty(stat.name())).append(FIELD_SEPARATOR)
                    .append(stat.path()).append(FIELD_SEPARATOR)
                    .append(stat.activeCount()).append(FIELD_SEPARATOR)
                    .append(stat.leftCount()).append('\n');
        }
        try {
            redisTemplate.opsForValue().set(REDIS_KEY, sb.toString(), REDIS_TTL);
        } catch (RuntimeException ex) {
            log.warn("写入部门目录缓存失败，下次请求仍会回源 Oracle", ex);
        }
    }

    private static List<DepartmentStat> parse(String content) {
        List<DepartmentStat> result = new ArrayList<>();
        for (String line : content.split("\n")) {
            String[] parts = line.split(FIELD_SEPARATOR, -1);
            if (parts.length != 5 || parts[2].isEmpty()) {
                continue;
            }
            result.add(new DepartmentStat(emptyToNull(parts[0]), emptyToNull(parts[1]), parts[2],
                    Integer.parseInt(parts[3]), Integer.parseInt(parts[4])));
        }
        return result;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}

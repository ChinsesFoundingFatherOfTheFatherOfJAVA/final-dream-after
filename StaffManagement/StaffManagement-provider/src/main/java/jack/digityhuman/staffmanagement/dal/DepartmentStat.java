package jack.digityhuman.staffmanagement.dal;

/**
 * 部门目录的一行：一个部门 + 它的在职/离职人数。
 *
 * <p>人数在这里就按在职/离职拆开存，这样切换状态口径时不必回库重查。
 *
 * @param code        组织编号
 * @param name        组织名称
 * @param path        组织全名称，"_" 分隔的全路径
 * @param activeCount 该部门本级在职人数
 * @param leftCount   该部门本级离职人数
 */
public record DepartmentStat(String code, String name, String path, int activeCount, int leftCount) {

    /** 按状态口径取本级人数。 */
    public int count(jack.digityhuman.staffmanagement.api.query.WorkStatus status) {
        return switch (status) {
            case ACTIVE -> activeCount;
            case LEFT -> leftCount;
            case ALL -> activeCount + leftCount;
        };
    }
}

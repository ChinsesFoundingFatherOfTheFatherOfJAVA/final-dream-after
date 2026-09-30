package jack.digityhuman.staffmanagement.sync.vo;

/**
 * Oracle 部门维度的一行（同步输入，FP-01/17）：编码、名称与完整层级路径。
 *
 * <p>视图里没有"上级组织编号"，父子关系由 {@code deptPath} 去掉最后一段推出来，见
 * {@code OracleSyncService#syncDepartments}。
 */
public record OracleDepartment(String deptCode, String deptName, String deptPath) {
}

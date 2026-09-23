package jack.digityhuman.staffmanagement.dal;

import jack.digityhuman.staffmanagement.api.dto.EmployeeDTO;
import jack.digityhuman.staffmanagement.api.dto.PageResult;
import jack.digityhuman.staffmanagement.api.query.WorkStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 视图 {@code JACKSHR.V_BD_JACKMES_INFO} 的只读访问。
 *
 * <p>刻意用 JdbcTemplate 直写 SQL 而不是 ORM：
 * <ol>
 *   <li>视图有 46 列、且是 DISTINCT + 8 个 LEFT JOIN 拼出来的，实体映射没有收益；</li>
 *   <li>库是 Oracle 11g，不支持 12c 的 {@code offset ... fetch next}，分页只能自己写 {@code rownum}；</li>
 *   <li>"组织编号" 这类中文列名必须带引号，手写 SQL 反而最直白。</li>
 * </ol>
 *
 * <p>硬约束：拼进 SQL 的只能是编译期常量，任何入参一律以 {@code ?} 绑定。
 * 没有 ORM 的 {@code #{}} 兜底，这条纪律就是这个类不存在注入面的唯一保证。
 */
@Repository
public class StaffViewRepository {

    /** 视图挂在 JACKSHR 下，当前连接账号是 JACKSHRORGUSER，不带 schema 前缀会 ORA-00942。 */
    private static final String VIEW = "JACKSHR.V_BD_JACKMES_INFO";

    /**
     * 投影列。中文列名一律起 ASCII 别名，结果集按别名取值。
     *
     * <p>{@code distinct} 不是可选的：视图里 101 个工号出现 2 行（合计 204 行），
     * 差异落在身份证/合同主体这类没被投影出来的列上；只投影业务字段后 DISTINCT 正好收敛成 34070 行，
     * 与视图里的员工数一致。
     */
    private static final String COLUMNS =
            "\"GH\" GH, \"NAME\" EMP_NAME, \"ENNAME\" EN_NAME, \"SEX\" SEX,"
            + " \"BIRTHDAY\" BIRTHDAY, \"HIREDATE\" HIRE_DATE, \"WORKAGE\" WORK_AGE, \"AGE\" AGE,"
            + " \"COMPANY\" COMPANY, \"COMPANYID\" COMPANY_ID,"
            + " \"EMPTYPECODE\" EMP_TYPE_CODE, \"EMPTYPENAME\" EMP_TYPE_NAME,"
            + " \"POSICODE\" POSI_CODE, \"POSINAME\" POSI_NAME,"
            + " \"组织编号\" DEPT_CODE, \"组织名称\" DEPT_NAME, \"组织全名称\" DEPT_PATH,"
            + " \"STATE\" STATE_CODE, \"ISWORK\" WORK_STATUS, \"EMPGROUP\" EMP_GROUP,"
            + " \"JOBGRADE\" JOB_GRADE, \"WORKPLACE\" WORKPLACE, \"HIGHESTDEGREE\" HIGHEST_DEGREE,"
            + " \"JIGUAN\" NATIVE_PLACE, \"DIRECT\" DIRECT_CODE, \"DIRECTNAME\" DIRECT_NAME,"
            + " \"SAPNUMBER\" SAP_NUMBER, \"NATIONALITY\" NATIONALITY,"
            + " \"EFFECTIVETIME\" EFFECTIVE_TIME, \"LASTCHANGE\" LAST_CHANGE";

    /** 敏感列，只有显式要求时才拼进投影。 */
    private static final String SENSITIVE_COLUMNS
            = ", \"FNCELL\" MOBILE, \"IDCARDNO\" ID_CARD_NO, \"IDTYPE\" ID_TYPE";

    private final JdbcTemplate jdbcTemplate;

    public StaffViewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // 视图本身就要 2~3 秒，给一个明确上限，避免慢查询把请求线程一直占住
        this.jdbcTemplate.setQueryTimeout(60);
    }

    /**
     * 关键字模糊查询，命中中文姓名、英文姓名或工号。
     *
     * <p>用 {@code count(*) over()} 在同一次扫描里顺带拿到总数，
     * 免得 count + 数据各扫一遍视图（那会让单次请求变成 5 秒）。
     * 只有翻到范围外的空页时才会退化成多扫一次 count。
     */
    public PageResult<EmployeeDTO> searchEmployees(String keyword, WorkStatus status, int page, int size,
                                                   boolean includeSensitive) {
        List<Object> args = new ArrayList<>();
        String like = "%" + escapeLike(keyword.trim()) + "%";
        StringBuilder where = new StringBuilder(
                "(\"NAME\" like ? escape '\\' or \"ENNAME\" like ? escape '\\' or \"GH\" like ? escape '\\')");
        args.add(like);
        args.add(like);
        args.add(like);
        appendStatus(where, args, status);
        return page(where.toString(), args, page, size, includeSensitive);
    }

    /**
     * 查某个部门子树的员工。
     *
     * <p>用 {@code substr(全名称, 1, 前缀长度) = 前缀} 而不是 {@code like 前缀 || '%'}：
     * 全路径的分隔符正好是 "_"，而 "_" 在 LIKE 里是单字符通配符，走 LIKE 得额外做转义。
     *
     * @param pathPrefix 目标部门的组织全名称，含目标部门自身
     */
    public PageResult<EmployeeDTO> findByDeptPath(String pathPrefix, WorkStatus status, int page, int size,
                                                  boolean includeSensitive) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("substr(\"组织全名称\", 1, ?) = ?");
        args.add(pathPrefix.length());
        args.add(pathPrefix);
        appendStatus(where, args, status);
        return page(where.toString(), args, page, size, includeSensitive);
    }

    /**
     * 加载全量部门目录（实测 1384 个部门），带每个部门的在职/离职人数。
     *
     * <p>内层必须按 (部门, 在职状态) 分组再 {@code count(distinct "GH")}：视图里 101 个工号有重复行，
     * 直接 {@code count(*)} 会多算；而写成 {@code select distinct ... } 再按状态数行数更错——
     * 那样每个部门每个状态只剩 1 行，人数会全部退化成 0/1。
     *
     * <p>一个工号只属于一个部门、一个在职状态（已按视图全量校验），所以各部门人数之和等于员工总数，
     * 可以直接和分页结果里的 total 对账。
     */
    public List<DepartmentStat> loadDepartmentDirectory() {
        String sql = "select DEPT_CODE, max(DEPT_NAME) DEPT_NAME, max(DEPT_PATH) DEPT_PATH,"
                + " sum(case when WORK_STATUS = '在职' then EMP_CNT else 0 end) ACTIVE_CNT,"
                + " sum(case when WORK_STATUS = '离职' then EMP_CNT else 0 end) LEFT_CNT"
                + " from (select \"组织编号\" DEPT_CODE, max(\"组织名称\") DEPT_NAME,"
                + " max(\"组织全名称\") DEPT_PATH, \"ISWORK\" WORK_STATUS,"
                + " count(distinct \"GH\") EMP_CNT from " + VIEW
                + " group by \"组织编号\", \"ISWORK\")"
                + " group by DEPT_CODE";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new DepartmentStat(
                rs.getString("DEPT_CODE"),
                rs.getString("DEPT_NAME"),
                rs.getString("DEPT_PATH"),
                rs.getInt("ACTIVE_CNT"),
                rs.getInt("LEFT_CNT")));
    }

    private PageResult<EmployeeDTO> page(String where, List<Object> args, int page, int size,
                                         boolean includeSensitive) {
        String inner = "select distinct " + COLUMNS
                + (includeSensitive ? SENSITIVE_COLUMNS : "")
                + " from " + VIEW + " where " + where;
        String sql = "select * from (select e.*, row_number() over (order by GH) RN__,"
                + " count(*) over () TOTAL__ from (" + inner + ") e) where RN__ between ? and ?";
        List<Object> all = new ArrayList<>(args);
        all.add((long) (page - 1) * size + 1);
        all.add((long) page * size);
        EmployeeRowMapper mapper = new EmployeeRowMapper(includeSensitive);
        return jdbcTemplate.query(sql, all.toArray(), rs -> {
            List<EmployeeDTO> records = new ArrayList<>();
            long total = -1;
            int index = 0;
            while (rs.next()) {
                records.add(mapper.mapRow(rs, index++));
                total = rs.getLong("TOTAL__");
            }
            // 窗口函数算出的总数只在"本页有行"时才拿得到：翻到范围外会一行都没有，
            // 这时补一次 count，否则 total 只能报 0，调用方会误以为整个查询没命中。
            if (total < 0 && page > 1) {
                total = count(inner, args);
            }
            return PageResult.of(page, size, Math.max(total, 0), records);
        });
    }

    /** 与分页完全同一个内层查询，只是不截窗口，保证补出来的 total 和窗口里看到的口径一致。 */
    private long count(String inner, List<Object> args) {
        Long value = jdbcTemplate.queryForObject("select count(*) CNT__ from (" + inner + ")",
                args.toArray(), Long.class);
        return value == null ? 0 : value;
    }

    private static void appendStatus(StringBuilder where, List<Object> args, WorkStatus status) {
        if (status != WorkStatus.ALL) {
            where.append(" and \"ISWORK\" = ?");
            args.add(status == WorkStatus.ACTIVE ? "在职" : "离职");
        }
    }

    /** 转义 LIKE 的通配符，让用户输入的 % 和 _ 按字面量匹配。 */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

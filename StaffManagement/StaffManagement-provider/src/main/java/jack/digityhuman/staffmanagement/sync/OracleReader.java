package jack.digityhuman.staffmanagement.sync;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle 员工视图 {@code JACKSHR.V_BD_JACKMES_INFO} 的同步侧读取（FP-17）。
 *
 * <p>与 {@code StaffViewRepository}（面向查询接口、按关键字/部门取一页）分开：这里的访问模式是
 * "按水位顺序扫全表"，SQL、超时和投影都不同，混在一个类里两边都会为了对方将就。
 *
 * <p>硬约束同既有仓储：拼进 SQL 的只有编译期常量，入参一律 {@code ?} 绑定。
 */
@Repository
public class OracleReader {

    private static final String VIEW = "JACKSHR.V_BD_JACKMES_INFO";

    /**
     * 员工投影：列名与 {@code sys_user} 的字段一一对应，中文列必须带引号并起 ASCII 别名。
     *
     * <p>{@code LASTCHANGE} 与 {@code GH} 必须投影出来：一个是增量水位，一个是同一水位内的
     * 二级排序键，缺一个都没法继续翻页（Oracle 的 DISTINCT 要求 ORDER BY 列在投影里）。
     */
    private static final String EMPLOYEE_COLUMNS =
            "\"GH\" GH, \"NAME\" EMP_NAME, \"FNCELL\" MOBILE,"
            + " \"组织编号\" DEPT_CODE, \"组织名称\" DEPT_NAME, \"组织全名称\" DEPT_PATH,"
            + " \"POSINAME\" POSI_NAME, \"JOBGRADE\" JOB_GRADE, \"HIREDATE\" HIRE_DATE,"
            + " \"ISWORK\" WORK_STATUS, \"LASTCHANGE\" LAST_CHANGE";

    /**
     * 视图是 DISTINCT + 8 个 LEFT JOIN 拼出来的，单批扫描实测秒级；
     * 全量同步要连续扫 70 批，给 5 分钟上限，比查询接口的 60 秒宽松但不无限挂着。
     */
    private static final int QUERY_TIMEOUT_SECONDS = 300;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 自建 {@link JdbcTemplate} 而不是注入现成的那个：超时挂在模板对象上，
     * 直接改共享 Bean 会把 {@code StaffViewRepository} 设定的 60 秒一起改掉。
     */
    public OracleReader(@Qualifier("oraclePooledDataSource") DataSource oracleDataSource) {
        this.jdbcTemplate = new JdbcTemplate(oracleDataSource);
        this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    /**
     * 按水位向后读一批员工。
     *
     * <p>翻页用 <b>(LASTCHANGE, GH) 组合键集</b>而不是 {@code rownum between} 偏移量：
     * 一边扫一边写 MySQL，偏移量会把新写入的行算进位置里，翻页就会漏行或重复。
     * 同一水位（同一 LASTCHANGE）往往有多行，所以必须带工号作二级键才能稳定续接。
     *
     * @param watermark 已处理到的水位；null 表示从头开始（全量首轮）
     * @param afterAccount 水位内已处理到的最后一个账号；null 表示该水位还没开始
     * @param limit 本批条数
     */
    public List<OracleEmployee> readEmployeesAfter(LocalDateTime watermark, String afterAccount, int limit) {
        List<Object> args = new ArrayList<>(3);
        String where;
        if (watermark == null) {
            where = "";
        } else if (afterAccount == null) {
            // 新一轮的开始：用 >= 把与上次水位同值的行全部重来一遍。
            // 上一轮是在水位线上中断的，只记下时间戳无法知道哪几行已经处理过，
            // 宁可重复读几行（upsert 幂等），也不能漏。
            where = " where \"LASTCHANGE\" >= ?";
            args.add(watermark);
        } else {
            where = " where (\"LASTCHANGE\" > ? or (\"LASTCHANGE\" = ? and \"GH\" > ?))";
            args.add(watermark);
            args.add(watermark);
            args.add(afterAccount);
        }
        // LASTCHANGE 为 NULL 的 42 行（实测）在上述任何条件下都不会出现：它们没有变更时间，
        // 增量本来就无从判断其是否变化，只能靠全量首轮收进来。
        String sql = "select * from (select distinct " + EMPLOYEE_COLUMNS + " from " + VIEW + where
                + " order by \"LASTCHANGE\", \"GH\") where rownum <= ?";
        args.add(limit);
        return jdbcTemplate.query(sql, args.toArray(), (rs, rowNum) -> toEmployee(rs));
    }

    /**
     * 全量部门维度（实测 1384 个）。按路径排序，使 {@code sys_department} 的自增 ID 与
     * 层级顺序一致，同级部门在树上的先后也就稳定。
     */
    public List<OracleDepartment> readDepartments() {
        String sql = "select distinct \"组织编号\" DEPT_CODE, \"组织名称\" DEPT_NAME, \"组织全名称\" DEPT_PATH"
                + " from " + VIEW + " order by DEPT_PATH";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OracleDepartment(
                rs.getString("DEPT_CODE"), rs.getString("DEPT_NAME"), rs.getString("DEPT_PATH")));
    }

    private static OracleEmployee toEmployee(ResultSet rs) throws SQLException {
        String account = rs.getString("GH");
        String mobile = blankToNull(rs.getString("MOBILE"));
        return new OracleEmployee(
                // 账号规则（已确认）：有工号用工号；视图实测工号无空值，手机号只是兜底
                account == null || account.isBlank() ? mobile : account.trim(),
                blankToNull(account),
                rs.getString("EMP_NAME"),
                // 视图里无手机号的人 FNCELL 是字符串 "0"，直接入库会在名册里显示成"0"
                "0".equals(mobile) ? null : mobile,
                rs.getString("DEPT_CODE"),
                rs.getString("DEPT_NAME"),
                rs.getString("DEPT_PATH"),
                rs.getString("POSI_NAME"),
                rs.getString("JOB_GRADE"),
                rs.getTimestamp("HIRE_DATE") == null ? null : rs.getTimestamp("HIRE_DATE").toLocalDateTime().toLocalDate(),
                "离职".equals(rs.getString("WORK_STATUS")),
                toLocalDateTime(rs.getTimestamp("LAST_CHANGE")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

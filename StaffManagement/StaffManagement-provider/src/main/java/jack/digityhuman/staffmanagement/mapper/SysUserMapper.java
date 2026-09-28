package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.mapper.row.AccountNameRow;
import jack.digityhuman.staffmanagement.mapper.row.DeptPathInfoRow;
import jack.digityhuman.staffmanagement.mapper.row.DeptPathCountRow;
import jack.digityhuman.staffmanagement.mapper.row.PositionCountRow;
import jack.digityhuman.staffmanagement.mapper.row.UserRoleNameRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code sys_user} 的数据访问。
 *
 * <p>多条件分页（部门子树、状态、字段搜索、岗位多选、超管）在
 * {@code UserAdminService} 里用 LambdaQueryWrapper 拼，这里只放 wrapper 表达不了的
 * 聚合与跨表回填，避免把 SQL 散落在两处。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 岗位 distinct + 人数（FP-03 岗位选择弹窗）。
     *
     * <p>数据源就是用户表本身，所以岗位名单随 Oracle 同步自动增删，不需要岗位管理页。
     * 空岗位名不参与统计；结果按人数倒序，同名岗位只出一行。
     */
    @Select("""
            <script>
            select position_name, count(*) user_count
            from sys_user
            where position_name is not null and position_name != ''
            <if test="keyword != null and keyword != ''">
              and position_name like concat('%', #{keyword}, '%')
            </if>
            group by position_name
            order by user_count desc, position_name
            </script>
            """)
    List<PositionCountRow> selectPositionCounts(@Param("keyword") String keyword);

    /** 职级 distinct（FP-06 字段搜索的候选值，本期前端用输入框，接口供校验与展示）。 */
    @Select("select distinct job_grade from sys_user where job_grade is not null and job_grade != '' order by job_grade")
    List<String> selectJobGrades();

    /** 批量取一组用户的角色名，供列表"角色"列展示（一次查完，避免每行一次的 N+1）。 */
    @Select("""
            <script>
            select ur.user_id, r.name role_name
            from sys_user_role ur
            join sys_role r on r.id = ur.role_id
            where ur.user_id in
            <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            order by ur.user_id, r.id
            </script>
            """)
    List<UserRoleNameRow> selectRoleNamesByUserIds(@Param("userIds") List<Long> userIds);

    /** 批量把账号还原成姓名，用于"创建人""添加人"这类只存了 account 的审计字段。 */
    @Select("""
            <script>
            select account, name from sys_user where account in
            <foreach collection="accounts" item="a" open="(" separator="," close=")">#{a}</foreach>
            </script>
            """)
    List<AccountNameRow> selectNamesByAccounts(@Param("accounts") List<String> accounts);

    /**
     * 同步任务的用户 upsert（FP-17）：一批一条语句，3.4 万行全量约 70 次往返。
     *
     * <p>更新分支<b>只列基础字段</b>，{@code password / status / is_super_admin / source / email}
     * 一概不写：口令、启停和超管归本地管理，Oracle 那边没有这些概念；把 NULL 的 email 也列进去
     * 反而会把本地补录的邮箱冲掉。
     *
     * <p>{@code as incoming} 是 MySQL 8.0.19+ 引用待插入行的写法（目标实例 9.2），
     * 旧写法 {@code VALUES(col)} 在 8.0.20 起已废弃。
     */
    @Insert("""
            <script>
            insert into sys_user (account, employee_no, name, mobile, dept_code, dept_name, dept_path,
                                  position_name, job_grade, entry_date, status, password, source, last_sync_time)
            values
            <foreach collection="list" item="u" separator=",">
              (#{u.account}, #{u.employeeNo}, #{u.name}, #{u.mobile}, #{u.deptCode}, #{u.deptName}, #{u.deptPath},
               #{u.positionName}, #{u.jobGrade}, #{u.entryDate}, #{u.status}, #{u.password}, #{u.source},
               #{u.lastSyncTime})
            </foreach>
            as incoming
            on duplicate key update
              employee_no = incoming.employee_no, name = incoming.name, mobile = incoming.mobile,
              dept_code = incoming.dept_code, dept_name = incoming.dept_name, dept_path = incoming.dept_path,
              position_name = incoming.position_name, job_grade = incoming.job_grade,
              entry_date = incoming.entry_date, last_sync_time = incoming.last_sync_time
            </script>
            """)
    int upsertBatch(@Param("list") List<SysUser> users);

    /**
     * 把 Oracle 侧已离职的账号置为禁用（FP-17）。
     *
     * <p>单独一条而不是塞进上面的 upsert：upsert 的更新分支永远不动 {@code status}，
     * 否则管理员手工启用的人会被下一轮同步又关掉。
     * {@code status = 1} 的过滤让返回值正好是"本轮真正被禁用的人数"，同步日志里的数字才有意义。
     */
    @Update("""
            <script>
            update sys_user set status = 0
            where status = 1 and account in
            <foreach collection="accounts" item="a" open="(" separator="," close=")">#{a}</foreach>
            </script>
            """)
    int disableByAccounts(@Param("accounts") List<String> accounts);

    /** 当前所有账号，供同步任务判断"这批里哪些是新增"（比逐行 select 少 3.4 万次往返）。 */
    @Select("select account from sys_user")
    List<String> selectAllAccounts();

    /** 按部门全路径统计全部用户数，部门树的人数上卷以它为输入。 */
    @Select("select dept_path, count(*) user_count from sys_user"
            + " where dept_path is not null and dept_path != '' group by dept_path")
    List<DeptPathCountRow> sumEnabledByDeptPath();

    /** 用户直属部门路径与编码；同一路径多行时由同步侧取首个非空编码。 */
    @Select("select dept_path, min(dept_code) dept_code from sys_user"
            + " where dept_path is not null and dept_path != '' group by dept_path")
    List<DeptPathInfoRow> selectDeptPathInfo();

    /** 全量同步收口：当前仍存在的 Oracle 来源账号。 */
    @Select("select account from sys_user where source = 'ORACLE'")
    List<String> selectOracleAccounts();
}

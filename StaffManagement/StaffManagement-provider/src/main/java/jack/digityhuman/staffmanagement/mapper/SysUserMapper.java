package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.mapper.row.AccountNameRow;
import jack.digityhuman.staffmanagement.mapper.row.DeptPathInfoRow;
import jack.digityhuman.staffmanagement.mapper.row.DeptPathCountRow;
import jack.digityhuman.staffmanagement.mapper.row.PositionCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

    List<SysUser> selectRowsByIds(@Param("ids") List<Long> ids);

    /** 按账号批量取整行（不含 password），供 Dubbo 契约 {@code listUsersByAccounts} 使用。 */
    List<SysUser> selectRowsByAccounts(@Param("accounts") List<String> accounts);

    /**
     * 在职候选用户（Dubbo 契约 {@code listActiveCandidates}）：{@code status=1}，
     * 可选按部门全路径前缀（本部门 + 全部下级）圈定，可选按姓名/账号/工号模糊，按姓名、账号排序。
     */
    List<SysUser> selectActiveCandidates(@Param("deptPath") String deptPath, @Param("keyword") String keyword);

    /** 按部门全路径统计启用用户数（{@code status=1}），供 Dubbo 契约 {@code countUsersGroupByDeptPath} 使用。 */
    List<DeptPathCountRow> countActiveByDeptPath();

    /**
     * 岗位 distinct + 人数（FP-03 岗位选择弹窗）。
     *
     * <p>数据源就是用户表本身，所以岗位名单随 Oracle 同步自动增删，不需要岗位管理页。
     * 空岗位名不参与统计；结果按人数倒序，同名岗位只出一行。
     */
    List<PositionCountRow> selectPositionCounts(@Param("keyword") String keyword);

    /** 职级 distinct（FP-06 字段搜索的候选值，本期前端用输入框，接口供校验与展示）。 */
    List<String> selectJobGrades();

    /** 批量把账号还原成姓名，用于"创建人""添加人"这类只存了 account 的审计字段。 */
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
    int upsertBatch(@Param("list") List<SysUser> users);

    /** 当前所有账号，供同步任务判断"这批里哪些是新增"（比逐行 select 少 3.4 万次往返）。 */
    List<String> selectAllAccounts();

    /** 按部门全路径统计全部用户数，部门树的人数上卷以它为输入。 */
    List<DeptPathCountRow> sumEnabledByDeptPath();

    /** 用户直属部门路径与编码；同一路径多行时由同步侧取首个非空编码。 */
    List<DeptPathInfoRow> selectDeptPathInfo();

    /** 全量同步收口：当前仍存在的 Oracle 来源账号。 */
    List<String> selectOracleAccounts();
}

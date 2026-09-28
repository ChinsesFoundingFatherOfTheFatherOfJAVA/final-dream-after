package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysDepartment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code sys_department} 的数据访问。
 *
 * <p>部门总量约 1400，部门树接口一次全量捞出、在内存里建树与过滤，所以查询侧不需要自定义 SQL。
 * 这里只有同步任务用到的整表重建与人数回写。
 */
@Mapper
public interface SysDepartmentMapper extends BaseMapper<SysDepartment> {

    /**
     * 清空部门表，配合 {@link #insertBatch} 做整表重建。
     *
     * <p>不用 upsert：员工路径删掉一个部门时，upsert 会把它的行留在表里，
     * 部门树就长出一个再也点不进、人数永远是 0 的幽灵节点。
     * 与插入在同一个事务里，读侧不会看到空表。
     */
    @Delete("delete from sys_department")
    int deleteAll();

    /** 批量插入部门，{@code user_count} 先落 0，随后由 {@link #updateUserCounts} 一次回填。 */
    @Insert("""
            <script>
            insert into sys_department (dept_code, dept_name, parent_path, dept_path, dept_level, sort, user_count)
            values
            <foreach collection="list" item="d" separator=",">
              (#{d.deptCode}, #{d.deptName}, #{d.parentPath}, #{d.deptPath}, #{d.deptLevel}, #{d.sort}, 0)
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<SysDepartment> departments);

    /**
     * 批量回写 {@code user_count}：一条语句按部门编码改一批，避免 1400 次往返。
     *
     * <p>用 {@code dept_path}（{@code uk_dept_path}）定位。合成节点没有 dept_code，
     * 路径才是唯一稳定的节点键。
     */
    @Update("""
            <script>
            update sys_department set user_count = case dept_path
            <foreach collection="list" item="d"> when #{d.deptPath} then #{d.userCount}</foreach>
            end
            where dept_path in
            <foreach collection="list" item="d" open="(" separator="," close=")">#{d.deptPath}</foreach>
            </script>
            """)
    int updateUserCounts(@Param("list") List<SysDepartment> departments);
}

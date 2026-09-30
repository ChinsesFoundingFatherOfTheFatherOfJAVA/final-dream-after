package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngSubjectDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 添加用户权限抽屉的数据源：部门 / 岗位 / 角色。
 */
@Service
@RequiredArgsConstructor
public class SubjectService {

    private final JdbcTemplate jdbcTemplate;

    public List<KngSubjectDtos.DeptNode> deptTree() {
        record Row(String id, String deptPath, String name, String parentPath, int userCount) {
        }
        List<Row> rows = jdbcTemplate.query("""
                select d.id, d.dept_path, d.dept_name, d.parent_path, coalesce(c.cnt, 0) direct_count
                from sys_department d
                left join (
                    select dept_path, count(*) cnt from sys_user
                    where status = 1 and dept_path is not null and dept_path != ''
                    group by dept_path
                ) c on c.dept_path = d.dept_path
                order by d.id
                """, (rs, rowNum) -> new Row(
                rs.getString("id"), rs.getString("dept_path"), rs.getString("dept_name"),
                rs.getString("parent_path"), rs.getInt("direct_count")));

        Map<String, KngSubjectDtos.DeptNode> byCode = new HashMap<>();
        for (Row row : rows) {
            KngSubjectDtos.DeptNode node = new KngSubjectDtos.DeptNode();
            node.setId(row.id());
            node.setName(row.name());
            node.setUserCount(row.userCount());
            byCode.put(row.deptPath(), node);
        }
        List<KngSubjectDtos.DeptNode> roots = new ArrayList<>();
        for (Row row : rows) {
            KngSubjectDtos.DeptNode node = byCode.get(row.deptPath());
            KngSubjectDtos.DeptNode parent = row.parentPath() == null ? null : byCode.get(row.parentPath());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    public KngSubjectDtos.PageResult<KngSubjectDtos.PositionRow> positions(
            int page, int pageSize, String name) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String keyword = name == null ? "" : name.trim();
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from (
                    select position_name from sys_user
                    where position_name is not null and position_name != ''
                      and (? = '' or position_name like concat('%', ?, '%'))
                    group by position_name
                ) t
                """, Long.class, keyword, keyword);
        List<KngSubjectDtos.PositionRow> records = jdbcTemplate.query("""
                select position_name, count(*) user_count
                from sys_user
                where position_name is not null and position_name != ''
                  and (? = '' or position_name like concat('%', ?, '%'))
                group by position_name
                order by user_count desc, position_name
                limit ? offset ?
                """, (rs, rowNum) -> {
            KngSubjectDtos.PositionRow row = new KngSubjectDtos.PositionRow();
            row.setName(rs.getString("position_name"));
            row.setUserCount(rs.getInt("user_count"));
            return row;
        }, keyword, keyword, safeSize, (safePage - 1) * safeSize);
        return new KngSubjectDtos.PageResult<>(total == null ? 0 : total, records);
    }

    public KngSubjectDtos.PageResult<KngSubjectDtos.RoleRow> roles(
            int page, int pageSize, String name) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String keyword = name == null ? "" : name.trim();
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from sys_role r
                where (? = '' or r.name like concat('%', ?, '%'))
                """, Long.class, keyword, keyword);
        List<KngSubjectDtos.RoleRow> records = jdbcTemplate.query("""
                select r.id, r.name, r.remark, count(ur.id) user_count
                from sys_role r
                left join sys_user_role ur on ur.role_id = r.id
                where (? = '' or r.name like concat('%', ?, '%'))
                group by r.id, r.name, r.remark
                order by r.id
                limit ? offset ?
                """, (rs, rowNum) -> {
            KngSubjectDtos.RoleRow row = new KngSubjectDtos.RoleRow();
            row.setId(String.valueOf(rs.getLong("id")));
            row.setName(rs.getString("name"));
            row.setRemark(rs.getString("remark"));
            row.setUserCount(rs.getInt("user_count"));
            return row;
        }, keyword, keyword, safeSize, (safePage - 1) * safeSize);
        return new KngSubjectDtos.PageResult<>(total == null ? 0 : total, records);
    }
}

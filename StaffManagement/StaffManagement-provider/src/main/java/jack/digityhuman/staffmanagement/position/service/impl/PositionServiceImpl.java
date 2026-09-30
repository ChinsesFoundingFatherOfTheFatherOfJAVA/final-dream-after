package jack.digityhuman.staffmanagement.position.service.impl;

import jack.digityhuman.staffmanagement.common.LikeUtil;
import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.mapper.row.PositionCountRow;
import jack.digityhuman.staffmanagement.position.service.PositionService;
import jack.digityhuman.staffmanagement.position.vo.PositionVo;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 岗位与职级的 distinct 查询（FP-03/06）。
 *
 * <p>数据源就是 {@code sys_user} 本身，所以本期不做岗位管理页：
 * 岗位名单随 Oracle 同步自动增删，永不与人员数据脱节。
 */
@Service
public class PositionServiceImpl implements PositionService {

    /** 岗位全量约 166 项、职级十余项，量级决定了分页放在内存里做。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final SysUserMapper userMapper;

    public PositionServiceImpl(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 岗位分页列表，按人数倒序、同人数按名称。
     *
     * <p>先一次拿全再截页：GROUP BY 的代价本来就在扫表那一下，
     * 交给分页插件只是多一次 count 查询，换不来什么。
     */
    public PageVo<PositionVo> pagePositions(String keyword, Integer page, Integer size) {
        int currentPage = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        String needle = keyword == null || keyword.isBlank() ? null : LikeUtil.escape(keyword.trim());

        List<PositionCountRow> rows = userMapper.selectPositionCounts(needle);
        int from = Math.min((currentPage - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());

        List<PositionVo> list = rows.subList(from, to).stream().map(row -> {
            PositionVo vo = new PositionVo();
            vo.setPositionName(row.getPositionName());
            vo.setUserCount(row.getUserCount());
            return vo;
        }).toList();
        return PageVo.of(list, rows.size(), currentPage, pageSize);
    }

    /** 职级 distinct 列表（字段搜索下拉的数据源）。 */
    public List<String> listJobGrades() {
        return userMapper.selectJobGrades();
    }
}

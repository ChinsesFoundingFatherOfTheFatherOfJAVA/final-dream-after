package jack.digityhuman.speechtraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.speechtraining.entity.StaffUser;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StaffUserMapper extends BaseMapper<StaffUser> {
    StaffUser selectActiveByAccount(@Param("account") String account);

    List<StaffUser> selectByAccounts(@Param("accounts") List<String> accounts);

    List<StaffUser> selectActiveCandidates(
            @Param("deptPath") String deptPath,
            @Param("keyword") String keyword);
}

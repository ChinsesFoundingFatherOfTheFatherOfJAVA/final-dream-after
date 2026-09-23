package jack.digityhuman.staffmanagement.dal;

import jack.digityhuman.staffmanagement.api.dto.EmployeeDTO;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 视图结果集 -&gt; {@link EmployeeDTO} 的映射。
 *
 * <p>所有列都用了 ASCII 别名（见 {@link StaffViewRepository} 的投影），避免按中文列名去结果集里取值。
 */
class EmployeeRowMapper implements RowMapper<EmployeeDTO> {

    private final boolean includeSensitive;

    EmployeeRowMapper(boolean includeSensitive) {
        this.includeSensitive = includeSensitive;
    }

    @Override
    public EmployeeDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
        EmployeeDTO e = new EmployeeDTO();
        e.setGh(rs.getString("GH"));
        e.setName(rs.getString("EMP_NAME"));
        e.setEnName(rs.getString("EN_NAME"));
        e.setSex(rs.getString("SEX"));
        e.setBirthday(toLocalDate(rs.getTimestamp("BIRTHDAY")));
        e.setHireDate(toLocalDateTime(rs.getTimestamp("HIRE_DATE")));
        e.setWorkAge(rs.getBigDecimal("WORK_AGE"));
        e.setAge(toInteger(rs, "AGE"));
        e.setCompany(rs.getString("COMPANY"));
        e.setCompanyId(rs.getString("COMPANY_ID"));
        e.setEmpTypeCode(rs.getString("EMP_TYPE_CODE"));
        e.setEmpTypeName(rs.getString("EMP_TYPE_NAME"));
        e.setPosiCode(rs.getString("POSI_CODE"));
        e.setPosiName(rs.getString("POSI_NAME"));

        String path = rs.getString("DEPT_PATH");
        e.setDeptCode(rs.getString("DEPT_CODE"));
        e.setDeptName(rs.getString("DEPT_NAME"));
        e.setDeptPath(path);
        e.setDeptLevel(path == null || path.isBlank() ? 0 : path.split("_").length);

        e.setStateCode(rs.getString("STATE_CODE"));
        e.setWorkStatus(rs.getString("WORK_STATUS"));
        e.setEmpGroup(rs.getString("EMP_GROUP"));
        e.setJobGrade(rs.getString("JOB_GRADE"));
        e.setWorkplace(rs.getString("WORKPLACE"));
        e.setHighestDegree(rs.getString("HIGHEST_DEGREE"));
        e.setNativePlace(rs.getString("NATIVE_PLACE"));
        e.setDirectCode(rs.getString("DIRECT_CODE"));
        e.setDirectName(rs.getString("DIRECT_NAME"));
        e.setSapNumber(rs.getString("SAP_NUMBER"));
        e.setNationality(rs.getString("NATIONALITY"));
        e.setEffectiveTime(toLocalDateTime(rs.getTimestamp("EFFECTIVE_TIME")));
        e.setLastChange(toLocalDateTime(rs.getTimestamp("LAST_CHANGE")));

        if (includeSensitive) {
            e.setMobile(rs.getString("MOBILE"));
            e.setIdCardNo(rs.getString("ID_CARD_NO"));
            e.setIdType(toInteger(rs, "ID_TYPE"));
        }
        return e;
    }

    /** NUMBER 列可能为 NULL，getInt 会把 NULL 读成 0，必须用 wasNull 区分。 */
    private static Integer toInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static LocalDate toLocalDate(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().toLocalDate();
    }
}

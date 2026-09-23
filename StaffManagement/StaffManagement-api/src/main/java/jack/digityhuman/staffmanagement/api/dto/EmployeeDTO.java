package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工信息，对应 Oracle 视图 {@code JACKSHR.V_BD_JACKMES_INFO} 的一行（一名员工一条）。
 *
 * <p>{@code mobile}、{@code idCardNo}、{@code idType} 属于敏感字段，只有查询入参
 * {@code includeSensitive=true} 时才会填充，否则为 null。
 */
@Data
public class EmployeeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工号（视图列 GH），员工业务主键 */
    private String gh;

    /** 中文姓名（NAME） */
    private String name;

    /** 英文姓名（ENNAME），外籍员工常用 */
    private String enName;

    /** 性别（SEX）：男 / 女 / - */
    private String sex;

    /** 出生日期（BIRTHDAY） */
    private LocalDate birthday;

    /** 入职日期（HIREDATE） */
    private LocalDateTime hireDate;

    /** 工龄（WORKAGE），视图里是 NUMBER(28,10) */
    private BigDecimal workAge;

    /** 年龄（AGE） */
    private Integer age;

    /** 劳动合同主体名称（COMPANY） */
    private String company;

    /** 劳动合同主体编码（COMPANYID） */
    private String companyId;

    /** 员工子组编码（EMPTYPECODE） */
    private String empTypeCode;

    /** 员工子组名称（EMPTYPENAME）：生产 / 科室 / 无 */
    private String empTypeName;

    /** 岗位编码（POSICODE） */
    private String posiCode;

    /** 岗位名称（POSINAME） */
    private String posiName;

    /** 所属部门编号（"组织编号"），部门唯一标识 */
    private String deptCode;

    /** 所属部门名称（"组织名称"），存在大量重名 */
    private String deptName;

    /** 所属部门全路径（"组织全名称"），形如 杰克集团_杰克科技股份_营销军团_东南亚战区 */
    private String deptPath;

    /** 所属部门在组织树中的层级，由全路径按 "_" 切分得到，根为 1 */
    private int deptLevel;

    /** 人员状态原始码（STATE）：009 主动离职、010 辞退、011 退休、013 自动离职、1 正式、2 试用 等 */
    private String stateCode;

    /** 在职标识（ISWORK）：在职 / 离职 */
    private String workStatus;

    /** 状态归类（EMPGROUP）：正式员工、试用员工、主动离职 等 */
    private String empGroup;

    /** 职级（JOBGRADE） */
    private String jobGrade;

    /** 工作地编码（WORKPLACE） */
    private String workplace;

    /** 最高学历（HIGHESTDEGREE） */
    private String highestDegree;

    /** 籍贯（JIGUAN） */
    private String nativePlace;

    /** 直属上级工号（DIRECT） */
    private String directCode;

    /** 直属上级姓名（DIRECTNAME） */
    private String directName;

    /** SAP 编号（SAPNUMBER） */
    private String sapNumber;

    /** 国籍（NATIONALITY） */
    private String nationality;

    /** 启效时间（EFFECTIVETIME） */
    private LocalDateTime effectiveTime;

    /** 最后修改时间（LASTCHANGE），视图取员工记录与工作地记录修改时间的较大值 */
    private LocalDateTime lastChange;

    /** 手机号（FNCELL），敏感字段 */
    private String mobile;

    /** 身份证号 / 外籍护照号（IDCARDNO），敏感字段 */
    private String idCardNo;

    /** 证件类型（IDTYPE），敏感字段 */
    private Integer idType;
}

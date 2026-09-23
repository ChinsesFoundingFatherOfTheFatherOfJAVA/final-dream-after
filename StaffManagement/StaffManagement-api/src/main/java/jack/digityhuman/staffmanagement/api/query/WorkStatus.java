package jack.digityhuman.staffmanagement.api.query;

/**
 * 员工在职状态口径。视图里 34173 行中离职占 25080 行，所以默认只放开在职。
 */
public enum WorkStatus {

    /** 在职：正式员工、试用、实习、特聘、劳务派遣、返聘、劳务外包、业务外包 */
    ACTIVE,

    /** 离职：主动离职、辞退、退休、自动离职 */
    LEFT,

    /** 不区分在职与离职 */
    ALL
}

package jack.digityhuman.staffmanagement;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 员工与权限中心：对外提供 HTTP 管理接口、Dubbo 员工查询，并从 Oracle 定时同步用户。
 *
 * <p>{@code @MapperScan} 覆盖两处：遗留的按层 {@code mapper} 包，
 * 以及按领域收敛的 {@code notification.mapper}（新代码一律进领域包，见 AGENTS §2）。
 * 扫出来的 Mapper 全部走 {@code DataSourceConfig} 里标了 {@code @Primary} 的 MySQL 数据源；
 * Oracle 侧仍由 {@code StaffViewRepository} 用 JdbcTemplate 直写方言 SQL，不在此列。
 */
@SpringBootApplication
@MapperScan({"jack.digityhuman.staffmanagement.mapper",
        "jack.digityhuman.staffmanagement.notification.mapper"})
public class StaffManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(StaffManagementApplication.class, args);
    }

}

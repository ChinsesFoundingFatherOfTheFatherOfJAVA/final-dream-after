package jack.digityhuman.staffmanagement.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 双数据源装配：MySQL 承载用户与权限中心的 {@code sys_*} 表，Oracle 只作为员工同步源。
 *
 * <p>两个 {@link DataSource} 都手动声明，Boot 的 {@code DataSourceAutoConfiguration}
 * （{@code @ConditionalOnMissingBean(DataSource)}）整体退让，{@code spring.datasource.*} 不再生效，
 * 连接参数改挂 {@code sm.mysql.*} / {@code sm.oracle.*} 前缀。
 *
 * <p>MySQL 标了 {@code @Primary}，因此 MyBatis-Plus 的自动装配（按主数据源建
 * {@code SqlSessionFactory}）和事务管理器都只认它；Oracle 侧一律只读、不参与事务，
 * 通过 {@code oracleJdbcTemplate} 访问。
 */
@Configuration
public class DataSourceConfig {

    /** 主数据源：final_dream 库的 sys_user / sys_department / sys_role 等 7 张表。 */
    @Bean
    @Primary
    @ConfigurationProperties("sm.mysql")
    public HikariDataSource mysqlDataSource() {
        return new HikariDataSource();
    }

    /**
     * 次数据源：Oracle 11g 的 {@code JACKSHR.V_BD_JACKMES_INFO} 视图，只读。
     *
     * <p>视图单次查询实测 2~3 秒、全量同步要连续扫，池子给到 8 就够，不给 HR 生产库加压力。
     */
    @Bean
    @ConfigurationProperties("sm.oracle")
    public HikariDataSource oraclePooledDataSource() {
        return new HikariDataSource();
    }

    /** 事务只包 MySQL；同步任务按批提交就靠它。 */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(@Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        return new DataSourceTransactionManager(mysqlDataSource);
    }

    /**
     * 既有员工查询（{@code StaffViewRepository}）用的模板。
     *
     * <p>{@code StaffViewRepository} 按类型注入 {@code JdbcTemplate}，这里全容器只有这一个
     * {@code JdbcTemplate}，所以它天然落在 Oracle 上；MyBatis-Plus 走的是 MySQL，不需要模板。
     */
    @Bean
    public JdbcTemplate oracleJdbcTemplate(@Qualifier("oraclePooledDataSource") DataSource oracleDataSource) {
        return new JdbcTemplate(oracleDataSource);
    }
}

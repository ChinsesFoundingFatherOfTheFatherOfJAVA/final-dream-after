package jack.digityhuman.common.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis-Plus 公共插件配置（after-end-common 提供，经 AutoConfiguration.imports 注册）。
 *
 * <p>分页插件必须注册，否则 Page 查询不会执行 count，total 恒为 0。方言固定 MySQL
 * （全站只有 StaffManagement 的 Oracle 侧用 JdbcTemplate，不经 MP）。
 */
@AutoConfiguration
public class MybatisPlusCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}

package org.dromara.project.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import javax.sql.DataSource;
import org.dromara.common.mybatis.config.MybatisPlusConfig;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * Real MyBatis-Plus mappings and the production pagination policy; SQL is only used for fixtures.
 */
public final class ProjectMybatisTestSupport {
    private ProjectMybatisTestSupport() {}

    public static SqlSessionTemplate session(DataSource source) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new MybatisPlusConfig().paginationInnerInterceptor());
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        var sessions = factory.getObject();
        sessions.getConfiguration().addMappers("org.dromara.project.mapper");
        return new SqlSessionTemplate(sessions);
    }
}

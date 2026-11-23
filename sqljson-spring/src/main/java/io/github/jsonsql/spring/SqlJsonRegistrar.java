package io.github.jsonsql.spring;

import io.github.jsonsql.SqlJson;
import io.github.jsonsql.SqlJsonRepository;
import io.github.jsonsql.support.SqlLog;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 扫描 {@code @SqlJson} 实体并注册仓库 Bean。
 *
 * <p>仓库实例用 Supplier 延迟创建：注册阶段只记下「类型 + DataSource 名字」，
 * 真正 new 出来的时机是第一次被注入时，这样不依赖 DataSource 的注册顺序，
 * 也不会拖慢容器启动（连库发生在业务第一次用到时）。
 */
public class SqlJsonRegistrar implements org.springframework.context.annotation.ImportBeanDefinitionRegistrar,
        BeanFactoryAware, EnvironmentAware, ResourceLoaderAware {

    private BeanFactory beanFactory;
    private Environment environment;
    private ResourceLoader resourceLoader;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    /**
     * 扫描并注册。
     *
     * <p>用 instanceSupplier 而不是构造参数引用 DataSource，有两个原因：
     * 一是注册阶段 DataSource 可能还没实例化，不能 {@code getBean} 提前触发；
     * 二是让连接真正发生在「第一次用到仓库」时，避免启动期就去连库。
     *
     * <p>找不到任何 {@code @SqlJson} 时直接抛异常而不是静默跳过——
     * 这类配置错误如果只是「没有 Bean」，排查会非常费时。
     */
    public void registerBeanDefinitions(org.springframework.core.type.AnnotationMetadata metadata,
                                        BeanDefinitionRegistry registry) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableSqlJson.class.getName());
        if (attrs == null) {
            return;
        }
        String[] packages = (String[]) attrs.get("basePackages");
        String dataSourceRef = (String) attrs.get("dataSourceRef");
        if (packages == null || packages.length == 0) {
            packages = new String[]{ClassUtils.getPackageName(metadata.getClassName())};
        }

        for (Class<?> type : scan(packages)) {
            String beanName = beanName(type);
            if (registry.containsBeanDefinition(beanName)) {
                continue;
            }
            String dsName = dataSourceRef == null || dataSourceRef.isBlank() ? "dataSource" : dataSourceRef;
            BeanDefinition definition = BeanDefinitionBuilder
                    .genericBeanDefinition(SqlJsonRepository.class,
                            () -> SqlJsonRepository.of(type, beanFactory.getBean(dsName, DataSource.class)))
                    .getBeanDefinition();
            // 说明：BeanDefinition 无法携带泛型信息，所以注入时需用 @Qualifier("userSqlJsonRepository")
            // 或让参数名与 Bean 名一致。这是 Spring 对 instanceSupplier 型 Bean 的固有限制。
            registry.registerBeanDefinition(beanName, definition);
            SqlLog.compiled("@SqlJson", type.getSimpleName() + " → Bean[" + beanName + "]", java.util.List.of());
        }
    }

    private Set<Class<?>> scan(String[] packages) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false, environment);
        scanner.setResourceLoader(resourceLoader);
        scanner.addIncludeFilter(new AnnotationTypeFilter(SqlJson.class));

        Set<Class<?>> types = new LinkedHashSet<>();
        for (String pkg : packages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(pkg)) {
                String name = candidate.getBeanClassName();
                if (name == null) {
                    continue;
                }
                try {
                    types.add(ClassUtils.forName(name, resourceLoader.getClassLoader()));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("无法加载实体类 " + name, e);
                }
            }
        }
        if (types.isEmpty()) {
            throw new IllegalStateException("在 " + String.join(", ", packages)
                    + " 下没有找到带 @SqlJson 的类，检查 basePackages 是否写错");
        }
        return types;
    }

    /**
     * user → userSqlJsonRepository。
     *
     * <p>故意不用 {@code userRepository}：业务代码里通常已经有一个同名的仓储类，
     * 两者重名会在容器里互相覆盖，而且报错信息完全看不出是这个原因。
     */
    private static String beanName(Class<?> type) {
        SqlJson ann = type.getAnnotation(SqlJson.class);
        String ns = ann == null || ann.value().isBlank() ? type.getSimpleName() : ann.value();
        return ns.substring(0, 1).toLowerCase() + ns.substring(1) + "SqlJsonRepository";
    }
}

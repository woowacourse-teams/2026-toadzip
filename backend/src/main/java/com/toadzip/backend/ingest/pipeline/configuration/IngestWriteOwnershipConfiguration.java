package com.toadzip.backend.ingest.pipeline.configuration;

import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

@Configuration(proxyBeanMethods = false)
// 소유권 검증이 실제 트랜잭션 안에서 행 잠금을 유지하도록 transaction advice를 먼저 실행한다.
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)
public class IngestWriteOwnershipConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor ingestWriteOwnershipAdvisor(
            TransactionAttributeSource transactionAttributes,
            ObjectProvider<IngestWriteOwnershipGuard> guard
    ) {
        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                // Spring Data의 내부 transaction advice는 별도 순서다. 쓰기는 Service/Store에서 검증한다.
                if (org.springframework.data.repository.Repository.class.isAssignableFrom(targetClass)) {
                    return false;
                }
                TransactionAttribute attribute = transactionAttributes.getTransactionAttribute(method, targetClass);
                return attribute != null && !attribute.isReadOnly();
            }
        };
        MethodInterceptor interceptor = invocation -> {
            guard.getObject().verifyWrite();
            return invocation.proceed();
        };
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE);
        return advisor;
    }
}

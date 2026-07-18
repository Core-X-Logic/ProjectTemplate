package com.mycompanyname.zero.saas.feature;

import com.mycompanyname.zero.saas.api.FeatureChecker;
import com.mycompanyname.zero.saas.api.RequiresFeature;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Enforces {@link RequiresFeature}. Runs with the default (lowest) advice precedence, i.e. inside
 * the method-security interceptor: authorization decides <em>who</em> may call, this decides whether
 * the caller's package <em>contains</em> the capability at all.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RequiresFeatureAspect {

    private final FeatureChecker featureChecker;

    @Before("@annotation(com.mycompanyname.zero.saas.api.RequiresFeature) "
            + "|| @within(com.mycompanyname.zero.saas.api.RequiresFeature)")
    public void enforce(JoinPoint joinPoint) {
        RequiresFeature annotation = requirementOf(joinPoint);
        if (annotation == null) {
            return;
        }
        for (String featureName : annotation.value()) {
            if (featureName == null || featureName.isBlank()) {
                continue;
            }
            if (!featureChecker.isEnabled(featureName)) {
                throw DomainException.forbidden(
                        "The feature '" + featureName + "' is not included in this tenant's package");
            }
        }
    }

    /**
     * The method-level annotation wins over the type-level one. {@code getMostSpecificMethod}
     * resolves the implementation method behind a proxy, so an annotation declared on the concrete
     * class is still found when the join point reports an interface method.
     */
    private RequiresFeature requirementOf(JoinPoint joinPoint) {
        Class<?> targetClass = joinPoint.getTarget() == null
                ? joinPoint.getSignature().getDeclaringType()
                : AopUtils.getTargetClass(joinPoint.getTarget());
        Method method = AopUtils.getMostSpecificMethod(
                ((MethodSignature) joinPoint.getSignature()).getMethod(), targetClass);
        RequiresFeature onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequiresFeature.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(targetClass, RequiresFeature.class);
    }
}

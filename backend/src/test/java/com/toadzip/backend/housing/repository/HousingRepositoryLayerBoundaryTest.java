package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HousingRepositoryLayerBoundaryTest {

    private static final String HOUSING_SERVICE_PACKAGE = "com.toadzip.backend.housing.service";

    @Test
    void housing_repository_구현_시그니처는_service_타입에_의존하지_않는다() {
        Stream<Class<?>> fieldTypes = Arrays.stream(ComplexSummaryQueryRepository.class.getDeclaredFields())
                .map(Field::getType);
        Stream<Class<?>> constructorTypes = Arrays.stream(
                        ComplexSummaryQueryRepository.class.getDeclaredConstructors()
                )
                .flatMap(this::parameterTypes);
        Stream<Class<?>> methodTypes = Arrays.stream(ComplexSummaryQueryRepository.class.getDeclaredMethods())
                .flatMap(this::signatureTypes);

        boolean hasServiceDependency = Stream.of(fieldTypes, constructorTypes, methodTypes)
                .flatMap(types -> types)
                .anyMatch(this::isHousingServiceType);

        assertFalse(hasServiceDependency);
    }

    private Stream<Class<?>> parameterTypes(Constructor<?> constructor) {
        return Arrays.stream(constructor.getParameterTypes());
    }

    private Stream<Class<?>> signatureTypes(Method method) {
        return Stream.concat(Stream.of(method.getReturnType()), Arrays.stream(method.getParameterTypes()));
    }

    private boolean isHousingServiceType(Class<?> type) {
        String packageName = type.getPackageName();
        return packageName.equals(HOUSING_SERVICE_PACKAGE)
                || packageName.startsWith(HOUSING_SERVICE_PACKAGE + ".");
    }
}

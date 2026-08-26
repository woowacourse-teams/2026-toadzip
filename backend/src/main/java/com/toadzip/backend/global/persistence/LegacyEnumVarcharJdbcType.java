package com.toadzip.backend.global.persistence;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hibernate.dialect.Dialect;
import org.hibernate.type.descriptor.converter.spi.BasicValueConverter;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType;

public class LegacyEnumVarcharJdbcType extends VarcharJdbcType {

    @Override
    public String getCheckCondition(
            String columnName,
            JavaType<?> javaType,
            BasicValueConverter<?, ?> converter,
            Dialect dialect
    ) {
        Class<?> javaTypeClass = javaType.getJavaTypeClass();
        if (!javaTypeClass.isEnum() || !LegacyStoredValue.class.isAssignableFrom(javaTypeClass)) {
            return super.getCheckCondition(columnName, javaType, converter, dialect);
        }
        return dialect.getCheckCondition(columnName, storedValues(javaTypeClass), this);
    }

    private Set<String> storedValues(Class<?> javaTypeClass) {
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(javaTypeClass.getEnumConstants())
                .map(LegacyStoredValue.class::cast)
                .map(LegacyStoredValue::storedValues)
                .forEach(values::addAll);
        return values;
    }
}

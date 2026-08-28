package com.toadzip.backend.housing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.toadzip.backend.housing.domain.ComplexSort;

public record ComplexSummaryCursor(
        ComplexSort sort,
        SortValue primaryValue,
        long complexId
) {
    public sealed interface SortValue permits DateValue, DecimalValue {

        Object jdbcValue();

        String encodedValue();
    }

    public record DateValue(LocalDate value) implements SortValue {
        public DateValue {
            Objects.requireNonNull(value);
        }

        @Override
        public Object jdbcValue() {
            return value;
        }

        @Override
        public String encodedValue() {
            return value.toString();
        }
    }

    public record DecimalValue(BigDecimal value) implements SortValue {
        public DecimalValue {
            Objects.requireNonNull(value);
        }

        @Override
        public Object jdbcValue() {
            return value;
        }

        @Override
        public String encodedValue() {
            return value.toPlainString();
        }
    }
}

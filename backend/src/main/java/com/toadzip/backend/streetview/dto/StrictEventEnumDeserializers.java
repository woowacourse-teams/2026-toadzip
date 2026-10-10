package com.toadzip.backend.streetview.dto;

import com.toadzip.backend.streetview.domain.StreetViewEvent.Phase;
import com.toadzip.backend.streetview.domain.StreetViewEvent.ReasonCode;
import com.toadzip.backend.streetview.domain.StreetViewEvent.Type;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public final class StrictEventEnumDeserializers {
    private StrictEventEnumDeserializers() {
    }

    public static final class EventType extends StrictEnum<Type> {
        public EventType() {
            super(Type.class);
        }
    }

    public static final class EventPhase extends StrictEnum<Phase> {
        public EventPhase() {
            super(Phase.class);
        }
    }

    public static final class EventReason extends StrictEnum<ReasonCode> {
        public EventReason() {
            super(ReasonCode.class);
        }
    }

    private abstract static class StrictEnum<E extends Enum<E>> extends ValueDeserializer<E> {
        private final Class<E> enumClass;

        private StrictEnum(Class<E> enumClass) {
            this.enumClass = enumClass;
        }

        @Override
        public E deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return enumClass.cast(context.handleUnexpectedToken(enumClass, parser));
            }
            try {
                return Enum.valueOf(enumClass, parser.getString());
            } catch (IllegalArgumentException exception) {
                return enumClass.cast(context.handleWeirdStringValue(enumClass, parser.getString(),
                        "정의된 enum 이름이 필요합니다."));
            }
        }
    }
}

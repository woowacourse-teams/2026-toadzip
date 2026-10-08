package com.toadzip.backend.streetview.controller;

import com.toadzip.backend.streetview.dto.StreetViewEventRequest;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException.Reason;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException;
import com.toadzip.backend.streetview.service.StreetViewEventAdmission;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice(assignableTypes = StreetViewEventController.class)
public class StreetViewEventBodyAdvice extends RequestBodyAdviceAdapter {
    public static final int MAX_BODY_BYTES = 4096;
    private final StreetViewEventAdmission admission;

    // Other MVC slices discover advice beans too, but do not need the event collection service.
    public StreetViewEventBodyAdvice(@Lazy StreetViewEventAdmission admission) {
        this.admission = admission;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == StreetViewEventRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        admission.enter();
        byte[] body = message.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new StreetViewCollectionException(Reason.PAYLOAD_TOO_LARGE);
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage message, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        admission.enter();
        return body;
    }
}

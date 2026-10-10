package com.toadzip.backend.streetview.service;

import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.repository.ComplexDetailQueryRepository;
import com.toadzip.backend.streetview.domain.StreetViewEvent.Type;
import com.toadzip.backend.streetview.domain.StreetViewEvent;
import com.toadzip.backend.streetview.dto.StreetViewEventRequest;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException;
import com.toadzip.backend.streetview.service.StreetViewAttemptTracker.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StreetViewEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreetViewEventService.class);
    private final ComplexDetailQueryRepository complexes;
    private final StreetViewAttemptTracker attempts;
    private final MeterRegistry meters;

    public StreetViewEventService(ComplexDetailQueryRepository complexes, StreetViewAttemptTracker attempts,
            MeterRegistry meters) {
        this.complexes = complexes;
        this.attempts = attempts;
        this.meters = meters;
    }

    public void collect(StreetViewEventRequest request) {
        // Validate the semantic combination before querying the database.
        var reported = new StreetViewEvent(request.attemptId(), request.complexId(), request.policyRevision(),
                request.type(), request.phase(), request.reasonCode(), request.durationMs());
        long canonicalId = complexes.findComplex(reported.complexId())
                .orElseThrow(HousingComplexNotFoundException::new).complexId();
        var event = new StreetViewEvent(reported.attemptId(), canonicalId, reported.policyRevision(), reported.type(),
                reported.phase(), reported.reasonCode(), reported.durationMs());
        Outcome outcome;
        try {
            outcome = attempts.record(event);
        } catch (StreetViewCollectionException exception) {
            meters.counter("street_view.collection.rejected", "reason", exception.getReason().name()).increment();
            throw exception;
        }
        if (outcome != Outcome.ACCEPTED) {
            meters.counter("street_view.collection.duplicates", "kind", outcome.name()).increment();
            return;
        }
        observe(event);
    }

    private void observe(StreetViewEvent event) {
        String reason = "NONE";
        if (event.reasonCode() != null) {
            reason = event.reasonCode().name();
        }
        meters.counter("street_view.events", "type", event.type().name(), "phase", event.phase().name(),
                "reasonCode", reason).increment();
        if (event.type() != Type.STARTED) {
            meters.timer("street_view.initialization.duration", "result", event.type().name())
                    .record(event.durationMs(), TimeUnit.MILLISECONDS);
        }
        if (event.type() == Type.FAILED) {
            LOGGER.warn("event=street_view.initialization.failed attemptId={} complexId={} policyRevision={} "
                            + "phase={} reasonCode={} durationMs={}", event.attemptId(), event.complexId(),
                    event.policyRevision(), event.phase(), reason, event.durationMs());
        }
    }
}

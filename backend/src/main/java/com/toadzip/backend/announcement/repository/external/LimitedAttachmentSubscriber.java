package com.toadzip.backend.announcement.repository.external;

import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException.Reason;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class LimitedAttachmentSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> downstream = HttpResponse.BodySubscribers.ofByteArray();
    private final long limit;
    private Flow.Subscription subscription;
    private long received;
    private boolean done;

    LimitedAttachmentSubscriber(long limit) {
        this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return downstream.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        downstream.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (done) {
            return;
        }
        received += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
        if (received > limit) {
            subscription.cancel();
            onError(new AttachmentUnavailableException(Reason.TOO_LARGE));
            return;
        }
        downstream.onNext(buffers);
    }

    @Override
    public void onError(Throwable error) {
        if (!done) {
            done = true;
            downstream.onError(error);
        }
    }

    @Override
    public void onComplete() {
        if (!done) {
            done = true;
            downstream.onComplete();
        }
    }
}

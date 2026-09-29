package com.toadzip.backend.announcement.repository.external;

import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException.Reason;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class LimitedAttachmentSubscriber implements HttpResponse.BodySubscriber<TemporaryAttachment> {
    private final CompletableFuture<TemporaryAttachment> body = new CompletableFuture<>();
    private final TemporaryAttachment file;
    private final long limit;
    private Flow.Subscription subscription;
    private long received;
    private boolean done;

    LimitedAttachmentSubscriber(long limit, TemporaryAttachment file) {
        this.limit = limit;
        this.file = file;
    }

    @Override
    public CompletionStage<TemporaryAttachment> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
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
        try {
            for (ByteBuffer buffer : buffers) {
                file.append(buffer);
            }
            subscription.request(1);
        } catch (IOException exception) {
            subscription.cancel();
            onError(exception);
        }
    }

    @Override
    public void onError(Throwable error) {
        if (!done) {
            done = true;
            body.completeExceptionally(error);
        }
    }

    @Override
    public void onComplete() {
        if (!done) {
            done = true;
            body.complete(file);
        }
    }
}

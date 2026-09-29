package com.toadzip.backend.announcement.repository.external;

import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException.Reason;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.util.unit.DataSize;

@Repository
public class AnnouncementAttachmentClient {
    private static final Set<String> HOSTS = Set.of("apply.lh.or.kr", "www.i-sh.co.kr", "www.gh.or.kr");

    private final Semaphore requests = new Semaphore(4);
    private final HttpClient client;
    private final Duration timeout;
    private final long maxBytes;

    @Autowired
    public AnnouncementAttachmentClient(
            @Value("${app.announcement.attachments.max-size:100MB}") DataSize maxSize
    ) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(120), maxSize.toBytes());
    }

    AnnouncementAttachmentClient(HttpClient client, Duration timeout) {
        this(client, timeout, 100L * 1024 * 1024);
    }

    AnnouncementAttachmentClient(HttpClient client, Duration timeout, long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Attachment max size must be positive");
        }
        this.maxBytes = maxBytes;
        this.client = client;
        this.timeout = timeout;
    }

    @PreDestroy
    public void close() {
        client.shutdownNow();
    }

    public TemporaryAttachment read(String source) {
        URI uri = allowedUri(source);
        if (!requests.tryAcquire()) {
            throw new AttachmentUnavailableException(Reason.BUSY);
        }
        TemporaryAttachment file;
        try {
            file = new TemporaryAttachment(requests::release);
        } catch (IOException exception) {
            requests.release();
            throw new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE);
        }
        try {
            return request(uri, file);
        } catch (RuntimeException exception) {
            try {
                file.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private URI allowedUri(String source) {
        try {
            URI uri = URI.create(source);
            boolean https = "https".equals(uri.getScheme());
            boolean http = "http".equals(uri.getScheme());
            if ((!https && !http) || uri.getHost() == null || !HOSTS.contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || !defaultPort(uri.getPort(), https)) {
                throw new AttachmentUnavailableException(Reason.UNSUPPORTED_SOURCE);
            }
            // Rebuild from the exact allowlisted host; never accept request-supplied destinations or redirects.
            String suffix = uri.getRawPath();
            if (uri.getRawQuery() != null) {
                suffix += "?" + uri.getRawQuery();
            }
            return URI.create("https://" + uri.getHost() + suffix);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new AttachmentUnavailableException(Reason.UNSUPPORTED_SOURCE);
        }
    }

    private boolean defaultPort(int port, boolean https) {
        if (port == -1) {
            return true;
        }
        if (https) {
            return port == 443;
        }
        return port == 80;
    }

    private TemporaryAttachment request(URI uri, TemporaryAttachment file) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept-Encoding", "identity").GET().build();
        CompletableFuture<HttpResponse<TemporaryAttachment>> response = client.sendAsync(request,
                info -> bodySubscriber(info, file));
        try {
            // The future completes only after headers AND the bounded body have arrived.
            TemporaryAttachment content = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS).body();
            if (content.size() == 0) {
                throw new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE);
            }
            return content;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE);
        } catch (TimeoutException exception) {
            throw new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE);
        } catch (ExecutionException exception) {
            throw translatedFailure(exception);
        } finally {
            response.cancel(true);
        }
    }

    private HttpResponse.BodySubscriber<TemporaryAttachment> bodySubscriber(
            HttpResponse.ResponseInfo info, TemporaryAttachment file
    ) {
        String type = info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (info.statusCode() != 200 || type.startsWith("text/html") || type.startsWith("application/xhtml+xml")) {
            throw new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE);
        }
        if (info.headers().firstValueAsLong("Content-Length").orElse(0) > maxBytes) {
            throw new AttachmentUnavailableException(Reason.TOO_LARGE);
        }
        return new LimitedAttachmentSubscriber(maxBytes, file);
    }

    private AttachmentUnavailableException translatedFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && !(cause instanceof AttachmentUnavailableException)) {
            cause = cause.getCause();
        }
        if (cause instanceof AttachmentUnavailableException unavailable) {
            return unavailable;
        }
        return new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE);
    }
}

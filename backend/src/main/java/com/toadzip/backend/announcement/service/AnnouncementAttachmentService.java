package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.dto.response.AttachmentContent;
import com.toadzip.backend.announcement.dto.response.AttachmentSource;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException.Reason;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class AnnouncementAttachmentService {
    private final AnnouncementAttachmentRepository repository;
    private final AnnouncementAttachmentClient client;

    public AnnouncementAttachmentService(
            AnnouncementAttachmentRepository repository,
            AnnouncementAttachmentClient client
    ) {
        this.repository = repository;
        this.client = client;
    }

    // Materialize the source without holding a database transaction during the external request.
    public AttachmentContent read(long announcementId, long attachmentId, boolean download) {
        AttachmentSource source = repository.findPublicSource(announcementId, attachmentId)
                .orElseThrow(() -> new AttachmentUnavailableException(Reason.NOT_FOUND));
        TemporaryAttachment file = client.read(source.fileUrl());
        try {
            byte[] bytes = file.prefix();
            String prefix = new String(bytes, StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
            if (download && (prefix.contains("<!doctype html") || prefix.contains("<html"))) {
                throw new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE);
            }
            if (!download && !isPdf(bytes)) {
                throw new AttachmentUnavailableException(Reason.NOT_PDF);
            }
            return new AttachmentContent(source.fileName(), file);
        } catch (IOException | RuntimeException exception) {
            try {
                file.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            if (exception instanceof AttachmentUnavailableException unavailable) {
                throw unavailable;
            }
            throw new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE);
        }
    }

    private boolean isPdf(byte[] bytes) {
        return bytes.length >= 5 && "%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII));
    }
}

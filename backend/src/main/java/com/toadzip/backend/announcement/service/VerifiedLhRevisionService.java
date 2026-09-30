package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.dto.request.VerifiedLhRevisionRequest;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifiedLhRevisionService {

    private final AnnouncementRepository repository;

    public VerifiedLhRevisionService(AnnouncementRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void link(long correctionId, VerifiedLhRevisionRequest request) {
        if (correctionId == request.previousAnnouncementId()) {
            throw new InvalidAnnouncementRequestException();
        }
        repository.findByIdForUpdate(Math.min(correctionId, request.previousAnnouncementId()))
                .orElseThrow(AnnouncementNotFoundException::new);
        repository.findByIdForUpdate(Math.max(correctionId, request.previousAnnouncementId()))
                .orElseThrow(AnnouncementNotFoundException::new);
        Announcement correction = repository.findById(correctionId).orElseThrow(AnnouncementNotFoundException::new);
        Announcement previous = repository.findById(request.previousAnnouncementId())
                .orElseThrow(AnnouncementNotFoundException::new);
        if (previous.getProvider() != AgencyCode.LH || correction.getProvider() != AgencyCode.LH
                || previous.getStatus() == AnnouncementPublicationType.CANCELLATION
                || correction.getStatus() == AnnouncementPublicationType.CANCELLATION
                || !matchesPan(previous, request.previousPanId()) || !matchesPan(correction, request.correctedPanId())
                || request.previousPanId().equals(request.correctedPanId())
                || !officialLhUrl(request.evidenceUrl())
                || repository.existsByPreviousAnnouncementAndIdNot(previous, correctionId)) {
            throw new InvalidAnnouncementRequestException();
        }
        Set<Long> visited = new HashSet<>();
        Announcement ancestor = previous;
        while (ancestor != null) {
            if (ancestor.getId().equals(correctionId) || !visited.add(ancestor.getId())) {
                throw new InvalidAnnouncementRequestException();
            }
            ancestor = ancestor.getPreviousAnnouncement();
        }
        if (correction.getPreviousAnnouncement() != null
                && !correction.getPreviousAnnouncement().getId().equals(previous.getId())) {
            throw new InvalidAnnouncementRequestException();
        }
        correction.confirmLhRevision(previous, request.previousPanId(), request.correctedPanId(),
                request.evidenceUrl(), request.reason());
    }

    private boolean matchesPan(Announcement announcement, String panId) {
        if (announcement.getLhPanId() != null) {
            return announcement.getLhPanId().equals(panId);
        }
        if (!officialLhUrl(announcement.getOriginalUrl())) {
            return false;
        }
        String query = URI.create(announcement.getOriginalUrl()).getRawQuery();
        if (query == null) {
            return false;
        }
        for (String parameter : query.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length == 2 && "panId".equals(pair[0])) {
                return panId.equals(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return false;
    }

    private boolean officialLhUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && host != null
                    && (host.equals("lh.or.kr") || host.endsWith(".lh.or.kr"));
        }
        catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}

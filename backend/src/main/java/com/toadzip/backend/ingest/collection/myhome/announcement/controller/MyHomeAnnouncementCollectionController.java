package com.toadzip.backend.ingest.collection.myhome.announcement.controller;

import com.toadzip.backend.ingest.exception.exception.RetiredIngestOperationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/myhome/announcements")
public class MyHomeAnnouncementCollectionController {

    @PostMapping
    public ResponseEntity<Void> collect() {
        throw RetiredIngestOperationException.myHomeAnnouncementCollection();
    }
}

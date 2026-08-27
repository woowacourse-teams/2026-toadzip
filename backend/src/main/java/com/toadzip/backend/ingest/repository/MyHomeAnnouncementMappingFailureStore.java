package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.MyHomeAnnouncementMappingFailure;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyHomeAnnouncementMappingFailureStore {

    private final MyHomeAnnouncementMappingFailureRepository repository;

    public MyHomeAnnouncementMappingFailureStore(MyHomeAnnouncementMappingFailureRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void replaceAll(List<MyHomeAnnouncementMappingFailure> failures) {
        repository.deleteAllInBatch();
        repository.saveAll(failures);
    }
}

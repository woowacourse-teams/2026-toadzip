package com.toadzip.backend.ingest.myhome;

import java.util.List;

public interface MyHomeNoticeSourceClient {

	List<MyHomeNoticeSourceItem> fetch(MyHomeNoticePageRequest request);

}

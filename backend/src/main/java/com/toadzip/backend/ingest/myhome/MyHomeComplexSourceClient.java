package com.toadzip.backend.ingest.myhome;

import java.util.List;

public interface MyHomeComplexSourceClient {

	List<MyHomeComplexSourceItem> fetch(MyHomeComplexPageRequest request);

}

package com.toadzip.backend.ingest.myhome;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;

@Component
public class MyHomeNoticeHttpSourceClient implements MyHomeNoticeSourceClient {

	private static final String PATH = "rsdtRcritNtcList";

	private static final String LIST_POINTER = "/response/body/item";

	private final DataGoKrOpenApiClient openApiClient;

	public MyHomeNoticeHttpSourceClient(@Qualifier("myHomeNoticeOpenApiClient") DataGoKrOpenApiClient openApiClient) {
		this.openApiClient = openApiClient;
	}

	@Override
	public List<MyHomeNoticeSourceItem> fetch(MyHomeNoticePageRequest request) {
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("suplyTy", request.supplyType().requestCode());
		params.add("pageNo", String.valueOf(request.page()));
		params.add("numOfRows", String.valueOf(request.pageSize()));
		return openApiClient.getList(PATH, params, LIST_POINTER, MyHomeNoticeSourceItem.class);
	}

}

package com.toadzip.backend.ingest.myhome;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;

@Component
public class MyHomeComplexHttpSourceClient implements MyHomeComplexSourceClient {

	private static final String PATH = "rentalHouseGwList";

	private static final String LIST_POINTER = "/response/body/item";

	private final DataGoKrOpenApiClient openApiClient;

	public MyHomeComplexHttpSourceClient(@Qualifier("myHomeComplexOpenApiClient") DataGoKrOpenApiClient openApiClient) {
		this.openApiClient = openApiClient;
	}

	@Override
	public List<MyHomeComplexSourceItem> fetch(MyHomeComplexPageRequest request) {
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("brtcCode", request.provinceCode());
		params.add("signguCode", request.districtCode());
		params.add("pageNo", String.valueOf(request.page()));
		params.add("numOfRows", String.valueOf(request.pageSize()));
		return openApiClient.getList(PATH, params, LIST_POINTER, MyHomeComplexSourceItem.class);
	}

}

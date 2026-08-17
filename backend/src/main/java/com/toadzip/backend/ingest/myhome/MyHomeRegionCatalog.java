package com.toadzip.backend.ingest.myhome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class MyHomeRegionCatalog {

	private static final String RESOURCE = "/myhome-region-codes.csv";

	private static final String HEADER = "brtcCode,signguCode,brtcName,signguName";

	private static final int REGION_COUNT = 256;

	private final List<MyHomeRegion> regions = load();

	public List<MyHomeRegion> all() {
		return regions;
	}

	private List<MyHomeRegion> load() {
		try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
			if (input == null) {
				throw new IllegalStateException("마이홈 지역 코드 파일이 없습니다.");
			}
			return read(input);
		}
		catch (IOException | IllegalArgumentException exception) {
			throw new IllegalStateException("마이홈 지역 코드 파일이 올바르지 않습니다.", exception);
		}
	}

	private List<MyHomeRegion> read(InputStream input) throws IOException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			if (!HEADER.equals(reader.readLine())) {
				throw new IllegalStateException("마이홈 지역 코드 헤더가 올바르지 않습니다.");
			}
			List<MyHomeRegion> loaded = readRegions(reader);
			if (loaded.size() != REGION_COUNT) {
				throw new IllegalStateException("마이홈 지역 코드는 256개여야 합니다.");
			}
			return List.copyOf(loaded);
		}
	}

	private List<MyHomeRegion> readRegions(BufferedReader reader) throws IOException {
		List<MyHomeRegion> loaded = new ArrayList<>();
		Set<String> fullCodes = new HashSet<>();
		String line = reader.readLine();
		while (line != null) {
			String[] columns = line.split(",", -1);
			if (columns.length != 4) {
				throw new IllegalStateException("마이홈 지역 코드 행이 올바르지 않습니다: " + line);
			}
			MyHomeRegion region = new MyHomeRegion(columns[0], columns[1], columns[2], columns[3]);
			if (!fullCodes.add(region.fullCode())) {
				throw new IllegalStateException("마이홈 지역 코드가 중복됩니다: " + region.fullCode());
			}
			loaded.add(region);
			line = reader.readLine();
		}
		return loaded;
	}

}

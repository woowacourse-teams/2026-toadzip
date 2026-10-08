package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 보완값은 원천 객체의 복사본에만 적용한다. 원천 식별자는 수정할 수 없다. */
@Component
public class IngestCorrectionOverlay {
    private static final Set<String> COMPLEX_FIELDS = Set.of("hsmpNm", "insttNm", "rnAdres", "pnu",
            "brtcCode", "signguCode", "hshldCo", "parkngCo", "styleNm", "suplyPrvuseAr", "suplyCmnuseAr",
            "competDe", "heatMthdDetailNm", "houseTyNm", "buldStleNm", "elvtrInstlAtNm");
    private static final Set<String> ANNOUNCEMENT_FIELDS = Set.of("pblancNm", "suplyInsttNm", "suplyTyNm",
            "houseTyNm", "sttusNm", "beforePblancId", "rcritPblancDe", "przwnerPresnatnDe", "beginDe",
            "endDe", "url", "pcUrl", "mobileUrl", "hsmpNm", "pnu", "sumSuplyCo", "fullAdres");
    private final ObjectMapper json;
    private static final Set<String> INTEGER_FIELDS = Set.of("hshldCo", "parkngCo", "sumSuplyCo");

    public IngestCorrectionOverlay(ObjectMapper json) {
        this.json = json;
    }

    public Map<String, Object> merge(String domain, Map<String, Object> original, Map<String, Object> changes) {
        Set<String> allowed = fields(domain);
        if (changes == null || !allowed.containsAll(changes.keySet())) {
            throw new InvalidIngestRequestException("보완할 수 없는 항목 또는 원천 식별자가 포함되어 있습니다.");
        }
        var result = new LinkedHashMap<>(original);
        changes.forEach((key, value) -> {
            if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                throw new InvalidIngestRequestException("보완 항목에는 문자열 또는 숫자를 입력해 주세요.");
            }
            if (value != null && INTEGER_FIELDS.contains(key)) {
                try {
                    new java.math.BigDecimal(value.toString()).intValueExact();
                }
                catch (NumberFormatException | ArithmeticException exception) {
                    throw new InvalidIngestRequestException("세대수와 주차대수에는 정수를 입력해 주세요.");
                }
            }
            result.put(key, value);
        });
        return result;
    }

    public Set<String> fields(String domain) {
        return switch (domain) {
            case "complex" -> COMPLEX_FIELDS;
            case "announcement" -> ANNOUNCEMENT_FIELDS;
            default -> throw new InvalidIngestRequestException("단지 또는 공고를 선택해 주세요.");
        };
    }

    public Map<String, Object> values(Object snapshot) {
        return json.convertValue(snapshot, new TypeReference<>() {});
    }
}

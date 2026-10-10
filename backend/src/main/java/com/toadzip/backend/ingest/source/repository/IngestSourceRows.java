package com.toadzip.backend.ingest.source.repository;

import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;

/** 관리자 원천 조회는 현행 수집 행만 조회한다. */
public final class IngestSourceRows {

    // Character.isWhitespace와 같은 문자 집합으로 기존 projection의 String.strip() 키를 재현한다.
    private static final String KEY_WHITESPACE = "U&'\\0009\\000A\\000B\\000C\\000D\\001C\\001D\\001E\\001F\\0020"
            + "\\1680\\2000\\2001\\2002\\2003\\2004\\2005\\2006\\2008\\2009\\200A"
            + "\\2028\\2029\\205F\\3000'";

    private IngestSourceRows() {
    }

    public static String query(IngestSourceCategory category) {
        return switch (category) {
            case SH_ANNOUNCEMENT -> """
                    SELECT source.id, source.source_key, source.title AS name, source.original_url,
                           source.collected_at, NULL::timestamptz AS updated_at,
                           to_jsonb(source)::text AS raw_payload
                    FROM sh_announcement_source source
                    """;
            case MYHOME_COMPLEX -> myHomeComplex();
            case MYHOME_ANNOUNCEMENT -> myHomeAnnouncement();
            case LH_LEASE_CATALOG -> """
                    SELECT r.id, r.id::text AS source_key, r.complex_label AS name,
                           NULL::text AS original_url, r.collected_at, NULL::timestamptz AS updated_at,
                           row_to_json(r)::text AS raw_payload
                    FROM lh_lease_catalog_source_rows r
                    JOIN lh_lease_catalog_source_bundles b ON b.id = r.source_id WHERE b.scope_key = 'ALL'
                    """;
            case LH_ANNOUNCEMENT_CATALOG -> """
                    SELECT current.id, current.source_key, current.raw_payload::jsonb ->> 'PAN_NM' AS name,
                           COALESCE(NULLIF(current.raw_payload::jsonb ->> 'DTL_URL', ''),
                                    NULLIF(current.raw_payload::jsonb ->> 'DTL_URL_MOB', '')) AS original_url,
                           current.collected_at, current.changed_at AS updated_at, current.raw_payload
                    FROM lh_announcement_catalog_entries current
                    """;
            case LH_ANNOUNCEMENT_DETAIL -> lhQuery(category, "lh_announcement_detail",
                    "CONCAT(q.pan_id, ':', q.request_hash, ':', r.dataset_type, ':', r.source_order)",
                    "COALESCE(r.complex_name, r.name, q.pan_id)");
            case LH_ANNOUNCEMENT_SUPPLY -> lhQuery(category, "lh_announcement_supply",
                    "CONCAT(q.pan_id, ':', q.request_hash, ':', r.source_order)", "r.complex_label");
        };
    }

    private static String myHomeComplex() {
        String key = keyPart("r.hsmp_sn") + " || " + keyPart("r.pnu") + " || "
                + keyPart("r.suply_ty_nm") + " || " + keyPart("r.style_nm") + " || "
                + keyPart("trim_scale(round(r.suply_prvuse_ar, 4))") + " || "
                + keyPart("trim_scale(round(r.suply_cmnuse_ar, 4))");
        return """
                SELECT r.id, %s AS source_key, r.hsmp_nm AS name, NULL::text AS original_url,
                       r.collected_at, NULL::timestamptz AS updated_at,
                       (to_jsonb(r) || jsonb_build_object('source_key', %s))::text AS raw_payload
                FROM myhome_complex_source_rows r
                """.formatted(key, key);
    }

    private static String myHomeAnnouncement() {
        String key = keyPart("r.pblanc_id") + " || " + keyPart("r.house_sn");
        return """
                SELECT r.id, %s AS source_key, r.pblanc_nm AS name,
                       COALESCE(NULLIF(r.url, ''), NULLIF(r.pc_url, ''), NULLIF(r.mobile_url, '')) AS original_url,
                       r.collected_at, NULL::timestamptz AS updated_at,
                       (to_jsonb(r) || jsonb_build_object('source_key', %s))::text AS raw_payload
                FROM myhome_announcement_source_rows r
                """.formatted(key, key);
    }

    private static String keyPart(String expression) {
        String stripped = "BTRIM((" + expression + ")::text, " + KEY_WHITESPACE + ")";
        return "CASE WHEN " + expression + " IS NULL THEN '-1:' ELSE LENGTH(" + stripped
                + ")::text || ':' || " + stripped + " END";
    }

    private static String lhQuery(IngestSourceCategory category, String table, String key, String name) {
        return """
                SELECT r.id, %s AS source_key, %s AS name, NULL::text AS original_url,
                       r.collected_at, NULL::timestamptz AS updated_at,
                       (to_jsonb(r) || jsonb_build_object('pan_id', q.pan_id,
                                                        'request_hash', q.request_hash))::text AS raw_payload
                FROM %s_rows r JOIN lh_announcement_query_sources q ON q.id = r.source_id
                WHERE q.source = '%s'
                """.formatted(key, name, table, category.name());
    }
}

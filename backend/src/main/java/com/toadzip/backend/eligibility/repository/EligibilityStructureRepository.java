package com.toadzip.backend.eligibility.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds searchable relationships from the same transaction's validated authored content. */
@Repository
public class EligibilityStructureRepository {
    private final JdbcClient jdbc;

    public EligibilityStructureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(long ruleSetId) {
        // Children disappear through same-rule-set foreign keys. No external input selects a table.
        execute("DELETE FROM comparison_supply_units WHERE rule_set_id = :id", ruleSetId);
        execute("DELETE FROM comparison_paths WHERE rule_set_id = :id", ruleSetId);
        execute("DELETE FROM comparison_qualifications WHERE rule_set_id = :id", ruleSetId);
        execute(
                """
                INSERT INTO comparison_supply_units
                SELECT r.id, u->>'id', u->>'label',
                       ARRAY(SELECT jsonb_array_elements_text(COALESCE(u->'regionCodes', '[]'))),
                       u->'housingTypes'
                FROM basic_comparison_rule_sets r,
                     LATERAL jsonb_array_elements(r.content->'supplyUnits') u
                WHERE r.id = :id
                """, ruleSetId);
        execute(
                """
                INSERT INTO comparison_paths
                SELECT r.id, p->>'id', p->>'label', p->>'kind', p->'checks'
                FROM basic_comparison_rule_sets r,
                     LATERAL jsonb_array_elements(r.content->'paths') p
                WHERE r.id = :id
                """, ruleSetId);
        execute(
                """
                INSERT INTO comparison_qualifications
                SELECT r.id, q->>'id', q->>'exactName', q->>'legalBasis', q->>'conditionsAsWritten',
                       ARRAY(SELECT jsonb_array_elements_text(COALESCE(q->'categories', '[]'))),
                       q->'evidenceIds'
                FROM basic_comparison_rule_sets r,
                     LATERAL jsonb_array_elements(COALESCE(r.content->'qualifications', '[]')) q
                WHERE r.id = :id
                """, ruleSetId);
        execute(
                """
                INSERT INTO comparison_path_qualifications
                SELECT r.id, p->>'id', q
                FROM basic_comparison_rule_sets r,
                     LATERAL jsonb_array_elements(r.content->'paths') p,
                     LATERAL jsonb_array_elements_text(COALESCE(p->'qualificationIds', '[]')) q
                WHERE r.id = :id
                """, ruleSetId);
        execute(
                """
                INSERT INTO comparison_options
                SELECT r.id, o->>'id', o->>'supplyUnitId', o->>'pathId', o->>'recruitmentStatus'
                FROM basic_comparison_rule_sets r,
                     LATERAL jsonb_array_elements(r.content->'options') o
                WHERE r.id = :id
                """, ruleSetId);
    }

    private void execute(String statement, long ruleSetId) {
        jdbc.sql(statement).param("id", ruleSetId).update();
    }
}

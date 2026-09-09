package com.axonect.ee.enterpriseintegration.domain.constant;

import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Column;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;

import java.util.List;

/**
 * The three table extracts, each defined by the header line the consuming system reads and the
 * column behind it.
 *
 * <p>The headers are the contract. They are taken from the sample extracts, not from the schema,
 * and they differ from it in ways that have to be preserved: the order is the sample's order and
 * not the DDL's, a column the sample omits stays omitted, and a column the sample renames keeps the
 * sample's name. {@code TableExtractsTest} asserts every header line so a well-meant tidy-up of
 * this file fails the build rather than the consumer's loader.
 */
public final class TableExtracts {

    public static final String MAC_SERVICE_TABLE_TYPE = "MAC_SERVICE_TABLE";
    public static final String PLAN_TO_BUCKET_TYPE = "PLAN_TO_BUCKET";
    public static final String BUCKET_INSTANCE_TYPE = "BUCKET_INSTANCE";

    /**
     * The service base: one row per SERVICE_INSTANCE.
     *
     * <p>Three columns are not a straight copy of the schema:
     *
     * <ul>
     *   <li>{@code SERVICE_ID} is SERVICE_INSTANCE.ID — the id BUCKET_INSTANCE.SERVICE_ID points
     *       at, which is what lets the consumer join this extract to the bucket extract.</li>
     *   <li>{@code RECURRING_FLAG} is a NUMBER(1) in the schema and Yes/No in the sample, so the
     *       mapping is done in SQL rather than left for the consumer to guess at.</li>
     *   <li>{@code GROUP_ID} is not a SERVICE_INSTANCE column at all. It is the subscriber's group
     *       on AAA_USER — the same column the user data dump reports under that name — which is
     *       why this is the one extract with a join. SERVICE_INSTANCE.IS_GROUP is a flag, not an
     *       id, and is deliberately not what is reported here.</li>
     * </ul>
     *
     * <p>The join is a LEFT join so a service whose username no longer resolves to a user is still
     * extracted, with an empty GROUP_ID, rather than silently dropped from the base.
     */
    public static final Spec MAC_SERVICE_TABLE = new Spec(
            MAC_SERVICE_TABLE_TYPE,
            "SERVICE_INSTANCE si LEFT JOIN AAA_USER u ON u.USER_NAME = si.USERNAME",
            List.of(
                    Column.plain("SERVICE_ID", "si.ID"),
                    Column.plain("USER_NAME", "si.USERNAME"),
                    Column.plain("PLAN_ID", "si.PLAN_ID"),
                    Column.plain("PLAN_NAME", "si.PLAN_NAME"),
                    Column.plain("PLAN_TYPE", "si.PLAN_TYPE"),
                    Column.plain("RECURRING_FLAG", "DECODE(si.RECURRING_FLAG, 1, 'Yes', 'No')"),
                    Column.at("SERVICE_CYCLE_START_DATE", "si.CYCLE_START_DATE"),
                    Column.at("SERVICE_CYCLE_END_DATE", "si.CYCLE_END_DATE"),
                    Column.at("NEXT_CYCLE_START_DATE", "si.NEXT_CYCLE_START_DATE"),
                    Column.at("SERVICE_START_DATE", "si.SERVICE_START_DATE"),
                    Column.plain("STATUS", "si.STATUS"),
                    Column.at("CREATED_AT", "si.CREATED_AT"),
                    Column.at("EXPIRY_DATE", "si.EXPIRY_DATE"),
                    Column.at("UPDATED_AT", "si.UPDATED_AT"),
                    Column.plain("GROUP_ID", "u.GROUP_ID")));

    /**
     * The plan catalogue's bucket definitions: one row per PLAN_TO_BUCKET row, every column of the
     * table in the sample's order.
     *
     * <p>This is the small one — a row per plan per bucket, thousands rather than millions — and it
     * is written by exactly the same path as the other two. A second, "simple" path for it would be
     * a second thing to keep correct for no gain: one cursor and a streaming writer cost nothing on
     * a few thousand rows.
     */
    public static final Spec PLAN_TO_BUCKET = new Spec(
            PLAN_TO_BUCKET_TYPE,
            "PLAN_TO_BUCKET p",
            List.of(
                    Column.plain("ID", "p.ID"),
                    Column.plain("BUCKET_ID", "p.BUCKET_ID"),
                    Column.plain("CARRY_FORWARD", "p.CARRY_FORWARD"),
                    Column.plain("CARRY_FORWARD_VALIDITY", "p.CARRY_FORWARD_VALIDITY"),
                    Column.plain("CONSUMPTION_LIMIT", "p.CONSUMPTION_LIMIT"),
                    Column.plain("CONSUMPTION_LIMIT_WINDOW", "p.CONSUMPTION_LIMIT_WINDOW"),
                    Column.at("CREATED_AT", "p.CREATED_AT"),
                    Column.plain("INITIAL_QUOTA", "p.INITIAL_QUOTA"),
                    Column.plain("MAX_CARRY_FORWARD", "p.MAX_CARRY_FORWARD"),
                    Column.plain("PLAN_ID", "p.PLAN_ID"),
                    Column.plain("TOTAL_CARRY_FORWARD", "p.TOTAL_CARRY_FORWARD"),
                    Column.at("UPDATED_AT", "p.UPDATED_AT")));

    /**
     * The live buckets: one row per BUCKET_INSTANCE, which is one per bucket per service and so the
     * largest of the three extracts.
     *
     * <p>Two departures from the DDL, both from the sample: {@code USAGE} is reported before
     * {@code UPDATED_AT} rather than after it, and {@code IS_UNLIMITED} is not part of the extract
     * at all even though the table carries it.
     */
    public static final Spec BUCKET_INSTANCE = new Spec(
            BUCKET_INSTANCE_TYPE,
            "BUCKET_INSTANCE b",
            List.of(
                    Column.plain("ID", "b.ID"),
                    Column.plain("BUCKET_ID", "b.BUCKET_ID"),
                    Column.plain("BUCKET_TYPE", "b.BUCKET_TYPE"),
                    Column.plain("CARRY_FORWARD", "b.CARRY_FORWARD"),
                    Column.plain("CARRY_FORWARD_VALIDITY", "b.CARRY_FORWARD_VALIDITY"),
                    Column.plain("CONSUMPTION_LIMIT", "b.CONSUMPTION_LIMIT"),
                    Column.plain("CONSUMPTION_LIMIT_WINDOW", "b.CONSUMPTION_LIMIT_WINDOW"),
                    Column.plain("CURRENT_BALANCE", "b.CURRENT_BALANCE"),
                    Column.at("EXPIRATION", "b.EXPIRATION"),
                    Column.plain("INITIAL_BALANCE", "b.INITIAL_BALANCE"),
                    Column.plain("MAX_CARRY_FORWARD", "b.MAX_CARRY_FORWARD"),
                    Column.plain("PRIORITY", "b.PRIORITY"),
                    Column.plain("RULE", "b.RULE"),
                    Column.plain("SERVICE_ID", "b.SERVICE_ID"),
                    Column.plain("TIME_WINDOW", "b.TIME_WINDOW"),
                    Column.plain("TOTAL_CARRY_FORWARD", "b.TOTAL_CARRY_FORWARD"),
                    Column.plain("USAGE", "b.USAGE"),
                    Column.at("UPDATED_AT", "b.UPDATED_AT")));

    /** Every extract, in the order they are registered. */
    public static final List<Spec> ALL = List.of(MAC_SERVICE_TABLE, PLAN_TO_BUCKET, BUCKET_INSTANCE);

    private TableExtracts() {
    }
}

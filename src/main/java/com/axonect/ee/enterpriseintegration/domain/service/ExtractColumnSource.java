package com.axonect.ee.enterpriseintegration.domain.service;

import java.sql.ResultSet;

/**
 * Fills the extract columns the statement selects nothing for.
 *
 * <p>A table extract is one CSV row per database row, and almost every column of one is a column
 * of the table. The exception is a figure the database does not hold: BUCKET_INSTANCE.USAGE, once
 * it reports what the CDR session documents say the bucket has drawn rather than what the table's
 * own counter says. Such a column is declared
 * {@link com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Column#spliced} —
 * kept in the header, left out of the select list — and filled here, from the row the cursor is
 * on and whatever this source is reading alongside it.
 *
 * <p>One source is opened per run and closed with it, so a source that holds a cursor of its own
 * holds it for exactly as long as the extract does, and gets the end of the run to report what it
 * filled the column from.
 */
public interface ExtractColumnSource extends AutoCloseable {

    /** An extract whose every column comes out of the database: nothing to splice in. */
    ExtractColumnSource NONE = (resultSet, row) -> {
    };

    /**
     * Fills this source's columns in {@code row}, which already carries every column the statement
     * did select. {@code resultSet} is on the row being written and must not be retained.
     */
    void fill(ResultSet resultSet, String[] row) throws Exception;

    @Override
    default void close() throws Exception {
        // Nothing to release by default.
    }
}

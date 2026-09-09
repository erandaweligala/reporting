package com.axonect.ee.enterpriseintegration.domain.repository;

import com.axonect.ee.enterpriseintegration.domain.constant.SqlStatement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Streams a report's rows straight off a JDBC cursor.
 *
 * <p>Deliberately plain JDBC rather than JPA. Hibernate would build a managed entity per row and
 * hold it in the persistence context, which for a few million rows means both a wasted object
 * graph and a first-level cache that grows until the heap gives out. Here a row never outlives the
 * callback that consumes it.
 *
 * <p>The connection is put in a read-only, non-committing, forward-only mode with a large fetch
 * size, which is what lets Oracle stream the result set to the client instead of the driver
 * buffering it or paging it back one row per round trip.
 */
@Component
@Slf4j
public class StreamingRowReader {

    /** Consumes the cursor's current row. Implementations must not retain the ResultSet. */
    @FunctionalInterface
    public interface RowHandler {
        void handle(ResultSet row) throws Exception;
    }

    private final DataSource dataSource;

    public StreamingRowReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs {@code statement} and feeds every row to {@code handler}.
     *
     * @return the number of rows read
     */
    public long stream(SqlStatement statement, int fetchSize, int timeoutSeconds, RowHandler handler)
            throws Exception {
        return stream(statement, fetchSize, timeoutSeconds, false, handler);
    }

    /**
     * As {@link #stream}, with the session forced into binary collation first.
     *
     * <p>Only a report whose ORDER BY has to agree with an ordering produced outside the database
     * needs this — the user data dump merge-joins its cursor against an Elasticsearch aggregation,
     * and a session that happened to default to a linguistic sort would silently mismatch users.
     * An extract that reads a table end to end has no ordering to agree with and does not pay for
     * the extra round trip.
     *
     * @return the number of rows read
     */
    public long streamInBinaryOrder(SqlStatement statement, int fetchSize, int timeoutSeconds, RowHandler handler)
            throws Exception {
        return stream(statement, fetchSize, timeoutSeconds, true, handler);
    }

    private long stream(SqlStatement statement, int fetchSize, int timeoutSeconds, boolean binarySort,
                        RowHandler handler) throws Exception {

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            if (binarySort) {
                applyBinarySort(connection);
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    statement.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

                ps.setFetchSize(fetchSize);
                ps.setQueryTimeout(timeoutSeconds);
                bind(ps, statement.params());

                long rows = 0;
                try (ResultSet rs = openCursor(ps, statement)) {
                    rs.setFetchSize(fetchSize);
                    while (rs.next()) {
                        handler.handle(rs);
                        rows++;
                    }
                }
                return rows;
            } finally {
                // A read-only cursor leaves nothing to commit, but the transaction the driver
                // opened still has to be closed out before the connection returns to the pool.
                rollbackQuietly(connection);
            }
        }
    }

    /**
     * Opens the cursor, putting the statement itself in the log if the database will not take it.
     *
     * <p>A report statement is assembled rather than written out, so an ORA-00907 or an ORA-00904
     * off one of these is otherwise unattributable: the exception names the complaint and nothing
     * about the text that provoked it, and the statement cannot be recovered from the logs to be
     * run by hand. The bound values are deliberately left out — the statement is the part that is
     * rejected, and the values are subscriber data.
     */
    private ResultSet openCursor(PreparedStatement ps, SqlStatement statement) throws SQLException {
        try {
            return ps.executeQuery();
        } catch (SQLException e) {
            log.error("The database rejected this report statement ({}): {}",
                    e.getMessage(), statement.sql());
            throw e;
        }
    }

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /**
     * Forces binary collation for this session so that an ORDER BY matches the UTF-8 ordering the
     * caller expects. It also keeps the ordered column's index usable for the sort instead of
     * forcing a multi-million row sort in temp space.
     */
    private void applyBinarySort(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER SESSION SET NLS_SORT='BINARY' NLS_COMP='BINARY'");
        } catch (SQLException e) {
            log.warn("Could not force binary collation on the reporting session; "
                    + "usage matching assumes the database already sorts usernames by byte value", e);
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("Failed to close the read-only reporting transaction", e);
        }
    }
}

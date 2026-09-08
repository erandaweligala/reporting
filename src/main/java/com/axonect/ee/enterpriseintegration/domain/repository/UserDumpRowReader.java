package com.axonect.ee.enterpriseintegration.domain.repository;

import com.axonect.ee.enterpriseintegration.domain.constant.UserDumpSql;
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
 * Streams the user dump query straight off a JDBC cursor.
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
public class UserDumpRowReader {

    /** Consumes the cursor's current row. Implementations must not retain the ResultSet. */
    @FunctionalInterface
    public interface RowHandler {
        void handle(ResultSet row) throws Exception;
    }

    private final DataSource dataSource;

    public UserDumpRowReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs {@code statement} and feeds every row to {@code handler}.
     *
     * @return the number of rows read
     */
    public long stream(UserDumpSql.Statement statement, int fetchSize, int timeoutSeconds, RowHandler handler)
            throws Exception {

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            applyBinarySort(connection);

            try (PreparedStatement ps = connection.prepareStatement(
                    statement.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

                ps.setFetchSize(fetchSize);
                ps.setQueryTimeout(timeoutSeconds);
                bind(ps, statement.params());

                long rows = 0;
                try (ResultSet rs = ps.executeQuery()) {
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

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /**
     * Forces binary collation for this session so that the ORDER BY matches the UTF-8 ordering the
     * Elasticsearch aggregation returns usernames in — the two are merge-joined, so a session that
     * happened to default to a linguistic sort would silently mismatch users. It also keeps the
     * username index usable for the sort instead of forcing a 3 million row sort in temp space.
     */
    private void applyBinarySort(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER SESSION SET NLS_SORT='BINARY' NLS_COMP='BINARY'");
        } catch (SQLException e) {
            log.warn("Could not force binary collation on the dump session; "
                    + "usage matching assumes the database already sorts usernames by byte value", e);
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("Failed to close the read-only dump transaction", e);
        }
    }
}

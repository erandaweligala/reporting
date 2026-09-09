package com.axonect.ee.enterpriseintegration.domain.constant;

import java.util.List;

/**
 * A SQL statement together with the positional parameters it expects, in placeholder order.
 *
 * <p>Every streaming report hands one of these to
 * {@link com.axonect.ee.enterpriseintegration.domain.repository.StreamingRowReader}: the statement
 * is built once per run and walked on a single cursor, so the shape of the statement and the
 * values bound to it travel together rather than being assembled at the reader.
 */
public record SqlStatement(String sql, List<Object> params) {
}

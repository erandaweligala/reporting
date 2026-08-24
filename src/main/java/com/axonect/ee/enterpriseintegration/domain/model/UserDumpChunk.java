package com.axonect.ee.enterpriseintegration.domain.model;

import java.util.List;

/**
 * One keyset page of the dump, plus the cursor to resume from.
 *
 * @param rows       rows in USER_ID order
 * @param lastUserId the USER_ID of the final row, the exclusive lower bound of the next page;
 *                   null when the page is empty
 */
public record UserDumpChunk(List<UserDumpRow> rows, String lastUserId) {

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}

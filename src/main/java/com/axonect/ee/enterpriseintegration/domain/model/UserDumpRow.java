package com.axonect.ee.enterpriseintegration.domain.model;

/**
 * One output row, already laid out in {@link UserDumpColumns} order, together with the two keys
 * needed to attach its Elasticsearch usage figure.
 *
 * <p>{@code userName} is carried separately because it is the key the session documents are
 * indexed under but is not itself an output column; {@code bucketId} identifies which of the
 * user's buckets the usage should be taken for.
 *
 * @param values   the row, exactly {@link UserDumpColumns#COLUMN_COUNT} entries long
 * @param userName AAA_USER.USER_NAME, the {@code userName} of the session documents
 * @param bucketId the bucket this row's quota belongs to, or null when the user has no bucket
 */
public record UserDumpRow(String[] values, String userName, String bucketId) {
}

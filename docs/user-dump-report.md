# USER_DUMP — daily subscriber data dump

A one-row-per-user extract of the whole subscriber base for a single day, written as CSV. It is
sized for the production base of roughly three million users.

Request it like any other report, with `reportType: "USER_DUMP"` and `format: "CSV"`. It covers
**D-1** (yesterday) unless the request carries a `reportDate` filter:

```json
{
  "reportType": "USER_DUMP",
  "format": "CSV",
  "filterValues": [
    { "columnName": "reportDate", "value": "2026-08-23", "operation": "eq" }
  ]
}
```

An unparseable or absent `reportDate` falls back to D-1, so a scheduled run needs no filter at all.

## Columns

39 columns, in this order — consumers read the file positionally, so the order and spelling are a
contract. `UTLIZED_QUOTA` is spelled that way on purpose; it is the header downstream already
parses. `UserDumpColumnsTest` pins the exact header line.

| # | Column | Source |
|---|---|---|
| 1 | `USER_ID` | `AAA_USER.USER_ID` |
| 2 | `GROUP_BANDWIDTH` | `AAA_USER.BANDWIDTH` |
| 3–9 | `BILLING` … `CONTACT_NUMBER` | `AAA_USER`, same-named columns |
| 10 | `CREATED_DATE` | `AAA_USER.CREATED_DATE` |
| 11–21 | `CUSTOM_TIMEOUT` … `NAS_PORT_TYPE` | `AAA_USER`, same-named columns |
| 22 | `ORIGINAL_MAC_ADDRESS` | `AAA_USER_MAC_ADDRESS.ORIGINAL_MAC_ADDRESS`, all of the user's rows joined with `,` |
| 23–28 | `REMOTE_ID` … `UPDATED_DATE` | `AAA_USER`, same-named columns |
| 29 | `SLMN` | `AAA_USER.SLMN` |
| 30 | `VLAN_ID` | `AAA_USER.VLAN_ID` |
| 31 | `NAS_IP_ADDRESS` | `AAA_USER.NAS_IP_ADDRESS` |
| 32 | `NOTIFICATION_TEMPLATES` | `AAA_USER.TEMPLATE_ID` |
| 33 | `CUSTOMER_ACTIVATION_DATE` | `AAA_USER.STATUS_CHANGED_DATE` |
| 34 | `BUNDLE_ACTIVATION_DATE` | `SERVICE_INSTANCE.SERVICE_START_DATE` |
| 35 | `BUNDLE_NAME` | `SERVICE_INSTANCE.PLAN_NAME` |
| 36 | `PLAN_BANDWIDTH` | `BUCKET_INSTANCE.BUCKET_ID` |
| 37 | `QUOTA` | `BUCKET_INSTANCE`: `Unlimited` when `IS_UNLIMITED = 1`, otherwise `INITIAL_BALANCE` |
| 38 | `UTLIZED_QUOTA` | Elasticsearch: the user's total usage on that bucket for the day |
| 39 | `BUNDLE_DEACTIVATION_DATE` | `SERVICE_INSTANCE.EXPIRY_DATE` |

Timestamps are written as `yyyy-MM-dd HH:mm:ss` (`report.user-dump.timestamp-format`) — one
unambiguous format for every date column, rather than the mix of Oracle-default and
spreadsheet-rendered formats a manual export produces. Values are otherwise verbatim, quoted per
RFC 4180 only when they contain a comma, quote or newline.

## What "for D-1" selects

The dump is a **snapshot of the base as it stood at the end of the day**, not a list of that day's
changes:

- **Users** — every user created before the end of the day. Users created later are not yet part of
  that day's base.
- **Bundle** — the user's `SERVICE_INSTANCE` that was live during the day: started before the day
  ended, and not expired before it began. A user with more than one such bundle contributes one
  row, for their `ACTIVE` bundle, most recently started first — so the row count equals the user
  count.
- **Bucket** — the bundle's lowest-`PRIORITY` bucket, which is the one the plan's quota and
  bandwidth are held on.
- **Usage** — summed from that day's session index only.

Users with no bundle, no bucket or no sessions still get a row, with those fields empty. The dump
is a complete list of the base; absence of a bundle is information, not a reason to drop the user.

## How it stays within memory and time budget

Three million rows is enough that the ordinary report path — which asks its source to skip N rows
to reach page N, and buffers each batch as maps — would not finish. This report takes a different
path (`StreamingReportDefinition`, dispatched by `UnifiedReportDownloadServiceImpl`):

- **Keyset pagination, not offset.** Pages resume from `USER_ID > :lastUserId`, so each page is a
  range scan into the primary key index and costs the same as the first. `OFFSET n ROWS` would make
  Oracle produce and discard n rows per page — O(rows²) over a full pass.
- **Correlated enrichment.** The MAC, service and bucket lookups are `OUTER APPLY` subqueries
  correlated to the page's users, so they touch only that page's child rows. Written as joins to
  grouped or windowed inline views they would re-aggregate the whole child table once per page.
- **One Elasticsearch request per page, not per user.** A composite aggregation keyed on
  `userName × bucketId` returns every total for the page in a single request. Per-user queries
  would be millions of round trips.
- **Constant heap.** Only one page of rows is ever live, held as `String[]` rather than per-row
  maps. Peak memory is a function of `chunk-size`, not of the size of the dump.
- **One open file.** The CSV stream is opened once and buffered, instead of being reopened and
  re-appended per batch.
- **Partial output is never published.** A mid-run failure marks the report `Failed` and deletes
  the partial file, so a truncated dump cannot be mistaken for a complete one.

## Required indexes

The correlated lookups are only cheap if the child tables are indexed on their lookup keys. Without
these, each page degrades into full scans of the child tables:

```sql
CREATE INDEX IDX_AAA_USER_MAC_USER_NAME ON AAA_USER_MAC_ADDRESS (USER_NAME);
CREATE INDEX IDX_SERVICE_INSTANCE_USERNAME ON SERVICE_INSTANCE (USERNAME);
CREATE INDEX IDX_BUCKET_INSTANCE_SERVICE  ON BUCKET_INSTANCE (SERVICE_ID);
```

`AAA_USER.USER_ID` is the primary key, so the keyset scan is already served.

Oracle 12.2 or later is required, for `OUTER APPLY` and for `LISTAGG … ON OVERFLOW TRUNCATE`.

## Configuration

| Key | Default | Notes |
|---|---|---|
| `report.user-dump.chunk-size` | `5000` | Users per database page and per Elasticsearch request. Raising it trades heap for fewer round trips; keep it well under Elasticsearch's `index.max_terms_count` (default 65536). |
| `report.user-dump.jdbc-fetch-size` | `2000` | Oracle row prefetch. |
| `report.user-dump.csv-buffer-bytes` | `1048576` | Output buffer. |
| `report.user-dump.timestamp-format` | `yyyy-MM-dd HH:mm:ss` | Applies to every date column. |
| `report.user-dump.unlimited-quota-label` | `Unlimited` | `QUOTA` value for an unlimited bucket. |
| `report.user-dump.timezone` | `${TZ:UTC}` | Zone D-1 is resolved in. **Must match cdr-service's `app.timezone`**, which decides the date suffix of the session index. |
| `report.user-dump.elk.index-prefix` | `radius-sessions` | cdr-service's `sessions-data`. |
| `report.user-dump.elk.composite-page-size` | `5000` | Aggregation page size. |
| `elasticsearch.*` | see `application.yml` | Host, port, credentials and pool for the session cluster. |

## Notes and caveats

- **Timezone.** If `report.user-dump.timezone` and cdr-service's `app.timezone` disagree, this
  report will look for a session index that either does not exist or covers a shifted day, and
  usage will be wrong or empty. Keep them equal.
- **Missing usage is not fatal.** If the day's index is absent or the cluster is unreachable, the
  dump is still produced with `UTLIZED_QUOTA` empty, and the shortfall is logged at `ERROR`. For a
  report of this cost, delivering it with one column missing beats delivering nothing.
- **Session instance mapping.** cdr-service lets `sessionInstances` map dynamically, so it is an
  object array rather than a `nested` field and a document's instance usages cannot be correlated
  per-element with their bucket ids. The aggregation is exact as long as a session charges a single
  bucket, which is how cdr-service assigns them. If that index is ever remapped as `nested`, wrap
  the aggregation in `SessionUsageAggregator` in a nested aggregation.
- **Sessions spanning midnight.** Usage is summed from the day's own index. A session that started
  the previous day lives in the previous day's index and is counted there, matching how
  cdr-service's own connection history reports a day.
- **`SLMN` and `NAS_IP_ADDRESS`.** These are read from `AAA_USER`, per the report specification.
  They do not appear in the schema snapshot the column mapping was drawn from, which lists 32
  columns ending at `STATUS_CHANGED_DATE`; the sample dump does carry a populated
  `NAS_IP_ADDRESS`, so the live table is expected to have them. If a deployment's `AAA_USER` does
  not, the query fails on startup of the report rather than silently — adjust the select list in
  `UserDumpRepository.SELECT_TEMPLATE`, which is the single place these columns are named.
- **CSV only.** The streaming path writes CSV; requesting `EXCEL` for this report type is rejected.
  A three-million-row XLSX is not a usable artifact — the format's own limit is 1,048,576 rows.

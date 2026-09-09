# USER_DATA_DUMP — the D-1 subscriber base extract

A single CSV holding one row per AAA user (~3 million), describing the bundle they held on the
previous day and how much of it they used. This note covers the shape of the run, the choices that
make it survive that row count, and the settings an operator needs.

## Where each column comes from

| Columns | Source |
| --- | --- |
| `USER_ID`, and every column not listed below | `AAA_USER` |
| `MAC_ADDRESS`, `ORIGINAL_MAC_ADDRESS` | `AAA_USER_MAC_ADDRESS`, comma-joined per user with `LISTAGG` (see below) |
| `BUNDLE_ACTIVATION_DATE`, `BUNDLE_NAME`, `BUNDLE_DEACTIVATION_DATE` | `SERVICE_INSTANCE` |
| `PLAN_BANDWIDTH`, `QUOTA` | `BUCKET_INSTANCE` |
| `SLMN` | the username — `AAA_USER` has no `SLMN` column |
| `NAS_IP_ADDRESS` | Elasticsearch: the NAS the user's D-1 sessions were anchored to |
| `UTLIZED_QUOTA` | Elasticsearch: the user's D-1 usage on the bundle's quota bucket |
| `CUSTOMER_ACTIVATION_DATE` | `AAA_USER.CREATED_DATE` — there is no separate activation column |

Column order is part of the contract with the consuming system and is asserted in
`UserDataDumpReportDefinitionTest`. `UTLIZED_QUOTA` is spelled the way the consumer spells it.

Three of those columns have no `AAA_USER` column of that name behind them. Two are stand-ins the
dump statement binds in the column's place: `SLMN` carries the username, and
`CUSTOMER_ACTIVATION_DATE` the created date. `NAS_IP_ADDRESS` has no stand-in worth binding — it
is the `nasIpAddress` cdr-service records on the session document from the CDR every accounting
event carries, so the statement selects nothing in that position and
`UserDataDumpReportDefinition` splices the value in as it lays the row out, from the same
Elasticsearch pass that already produces `UTLIZED_QUOTA` rather than from a lookup of its own. A
user whose sessions named more than one NAS that day is reported under the one most of them used;
a user with no session that day gets an empty column, exactly as they get an empty
`UTLIZED_QUOTA`.

Two more parts of that contract are fixed by the sample extract rather than by our own taste. Every
timestamp is written as `yyyy-MM-dd HH:mm:ss.SSS` — the `date-format` below is the Oracle model that
produces it, and because it carries fractional seconds each column is CAST to `TIMESTAMP` before
`TO_CHAR` sees it (`TO_CHAR` of a `DATE` with an `FF` element raises ORA-01821, and the dump does not
depend on which of the two each column happens to be). And `QUOTA` is left **empty** for a bundle
whose data bucket is unlimited: the consumer reads a blank there as "no cap", so `UserDumpSql`
excludes unlimited buckets from the pivot rather than writing a label into the column.

## How a run is shaped

```
        username keyspace, cut into N contiguous ranges
        ┌───────────────┬───────────────┬───────────────┐
shard   │   [ , "g")    │   ["g", "s")  │   ["s",  )    │
        └───────┬───────┴───────┬───────┴───────┬───────┘
                │               │               │
   Oracle  ─────┤ one cursor, ORDER BY USER_NAME│
   ES      ─────┤ composite agg, same range     │      merge-joined in step
                │               │               │
             dump.csv        part-1          part-2      → concatenated in shard order
```

**One query, not four.** The MAC addresses, the active bundle and its buckets are folded into the
dump statement as pre-aggregated inline views (`UserDumpSql`). Fetching them per user would be
three round trips per row — around nine million on a three million row dump. Each view collapses
its table in one pass and is hash-joined; the bucket view is joined to the bundle view so the
optimizer prunes it to the shard's own services rather than scanning `BUCKET_INSTANCE` whole.

**MAC lists truncated, not overflowed.** `LISTAGG` returns a `VARCHAR2`, so a user with enough
MAC addresses to push the joined list past 4 000 bytes would raise ORA-01489 and fail the run.
Oracle has a clause for exactly that — `ON OVERFLOW TRUNCATE` — but it only parses on 12.2 and
later: an older server reaches the `ON` where it expects the closing bracket of the argument list
and rejects the whole statement with **ORA-00907 (missing right parenthesis)**, which is a failed
dump rather than a shortened MAC list. So `UserDumpSql` spends the byte budget itself, in the
inline view: a running total charges each row the wider of its two addresses, rows past 4 000 bytes
never reach the aggregate, and `LISTAGG` is left in syntax every supported Oracle understands.
Cutting both lists at the same row also keeps `MAC_ADDRESS` and `ORIGINAL_MAC_ADDRESS`
row-aligned, which a per-column overflow clause would not. Do not put the overflow clause back
without first establishing the server version.

**One cursor, not pages.** Rows stream off an open, read-only, forward-only JDBC cursor with a
5 000 row fetch size (`StreamingRowReader`). Offset pagination — what the paged report framework
does — re-walks and discards everything before each page, so the cost of the last page grows with
the size of the report.

**Text conversion in the database.** Timestamps and numbers are converted with `TO_CHAR` in SQL, so
the reader pulls every column with `getString`. At three million rows the `Timestamp` and
`BigDecimal` objects a typed read would allocate, not the I/O, are what drives the collector.

**Elasticsearch merge-joined, not looked up.** Both sides are read in username order and consumed
in step (`UserUsageCursor`). Per-user lookups would be three million Elasticsearch round trips; a
pre-built map would cost a full aggregation scan and hundreds of megabytes before the first row
could be written. Instead nothing is held except the row being written and the aggregation page
being drained, so heap use is flat regardless of user count and the two scans overlap. Usage comes
from a composite aggregation over the single `radius-sessions-yyyy.MM.dd` index for D-1 — one
number per user per bucket over the wire, paged through `after_key`, with no scroll context left
open on the cluster.

The two orderings have to agree for the merge to work. Oracle's binary collation and
Elasticsearch's keyword ordering both compare UTF-8 bytes: the dump session forces
`NLS_SORT=BINARY`, and `Utf8Order` gives Java the same ordering (`String.compareTo` disagrees for
code points above U+FFFF).

**A writer that stays open.** `StreamingCsvWriter` holds one buffered handle for the life of the
dump and formats straight into it. The batch exporter rebuilds a multi-megabyte String per batch
and reopens the file for every append — invisible at a few thousand rows, dominant at three
million. Values are written exactly as the database produced them; the batch exporter's habit of
rewriting date-looking values into an Excel formula is deliberately not carried over, since a dump
consumed by another system needs the opposite.

**Shards, not threads over rows.** The username keyspace is cut into contiguous ranges
(`UserDumpShardPlanner`) that both Oracle and Elasticsearch can filter on, so each shard is a
genuinely independent scan on both sides. Shard 0 writes into the output file itself — it already
carries the header — and later shards write parts that are appended with `FileChannel.transferTo`,
inside the file system rather than through the heap. Shards run on their own pool
(`userDumpExecutor`) so a dump cannot starve the other reports of their slots.

Elasticsearch is only aggregated, never queried for hits, and only the D-1 index is named — the
dump never touches the index cdr-service is currently writing into.

## Running one

`USER_DATA_DUMP` is registered like any other report type and picked up by
`UnifiedReportDownloadServiceImpl`, which routes it down the streaming path instead of the paged
loop. It is produced as CSV only; requesting `EXCEL` fails fast rather than attempting a
spreadsheet of this size.

A run is two calls. The first asks for the dump and returns immediately — the dump itself is
generated on the report executor, and at three million rows that takes minutes, not the life of an
HTTP request:

```
POST /api/report-download/create
userId: <operator>
{ "reportType": "USER_DATA_DUMP", "classificationLevel": "Confidential" }
```

`format` may be given as `CSV`, but it does not need to be: a request that leaves it out is written
as CSV for `USER_DATA_DUMP`, because a streaming report is CSV-only and defaulting it to the
spreadsheet every other report gets would fail the run instead. Naming `EXCEL` here still fails, by
design.

The second call retrieves the finished file. `id` is the `REPORT_DOWNLOAD` row's id — the create
call does not return it, so it is read back from `POST /api/report-management/filter`, which is
also where the run's status (`Pending` → `Processing` → `Completed`, or `Failed`/`No Records`)
is visible:

```
GET /api/report-download/download?id={id}
```

The response is the dump itself, as `text/csv`, named
`{id}_USER_DATA_DUMP_yyyy_MM_dd_HH_mm_ss.csv` — the file `report.output.directory` holds. Asking
for it before the run finishes answers `4005 Report file not found` along with the status the
report is currently in, so a poll on that endpoint is a legitimate way to wait for the dump.

## Settings

```yaml
report:
  user-dump:
    days-back: 1                 # 1 = D-1
    timezone: "${TZ:UTC}"        # must match the zone the CDR daily indices are named in
    shards: 4                    # username ranges scanned in parallel; 1 disables sharding
    worker-threads: 0            # 0 = one thread per shard
    jdbc-fetch-size: 5000
    query-timeout-seconds: 7200
    csv-buffer-bytes: 1048576
    date-format: "YYYY-MM-DD HH24:MI:SS.FF3"
    bandwidth-bucket-type: BANDWIDTH
    quota-bucket-type: DATA
    usage:
      enabled: true
      index: radius-sessions     # cdr-service `sessions-data`
      page-size: 2000
      buckets-per-user: 20
      nas-addresses-per-user: 5  # distinct NAS addresses weighed before NAS_IP_ADDRESS is picked
      nas-ip-field: nasIpAddress.keyword
      nested: true
      instances-path: sessionInstances
```

`usage.enabled: false` leaves both Elasticsearch-filled columns — `UTLIZED_QUOTA` and
`NAS_IP_ADDRESS` — empty rather than failing the run.

`usage.nested` must describe how `sessionInstances` is actually mapped. Mapped as `nested`, usage
can be summed per bucket. Mapped as a plain object, Elasticsearch flattens the array and a
per-bucket sum would credit every bucket with the whole session's usage — so in that case the
user's whole day is reported instead, which is wrong for a multi-bucket bundle but not silently
wrong in the way the flattened sum would be. Fixing the mapping is the better answer.

`shards` costs one Oracle cursor and one Elasticsearch aggregation each. Raising it past the
database's appetite for concurrent full scans will slow the dump down, not speed it up; 4 is a
starting point, not a maximum.

## Assumptions worth confirming against the schema

- The join key across `AAA_USER`, `AAA_USER_MAC_ADDRESS` (`USER_NAME`) and `SERVICE_INSTANCE`
  (`USERNAME`) is the RADIUS username, and it is what the dump reports as `USER_ID` — which is
  what the sample extract shows and what the CDR documents are keyed on.
- `MAC_ADDRESS` is taken from `AAA_USER_MAC_ADDRESS` alongside `ORIGINAL_MAC_ADDRESS`, not from
  `AAA_USER`. The sample extract carries three comma-separated values in both columns, which only
  the per-MAC table can produce.
- `BUCKET_INSTANCE.BUCKET_TYPE` distinguishes the bandwidth bucket from the data bucket, and
  `BUCKET_ID` carries the bandwidth name (`FTTH_50Mbps` in the sample). Both type values are
  configurable above. `IS_UNLIMITED = 1` marks a bucket with no cap, and such a bucket is reported
  as an empty `QUOTA`.
- `CYCLE_DATE` is not a timestamp. It is the one date-named column the dump does not run through
  `TO_CHAR`, so if it turns out to be an Oracle `DATE` it will come back in the session's NLS
  format rather than the dump's — it is blank in the sample extract, so the schema is the only
  place to settle it.
- `BUNDLE_ACTIVATION_DATE` is `SERVICE_INSTANCE.SERVICE_START_DATE` and `BUNDLE_DEACTIVATION_DATE`
  is `EXPIRY_DATE`; where a user has held several bundles, the one active on D-1 is reported, most
  recent first.
- `AAA_USER` has no `SLMN`, `NAS_IP_ADDRESS` or `CUSTOMER_ACTIVATION_DATE` column, which is why
  those three are filled as described at the top. If a column for any of them is added later,
  selecting it is a one-line change in `UserDumpSql` — plus, for `NAS_IP_ADDRESS`, the column
  positions in `UserDataDumpReportDefinition`, since that one shifts the result set.
- The CDR session documents carry `nasIpAddress` with a `keyword` sub-field, the same shape the
  usage join already assumes of `userName`. An aggregation on a field the mapping does not have
  returns no terms rather than an error, so a wrong guess here shows up as an empty column and not
  as a failed run — `usage.nas-ip-field` is the knob that fixes it without a rebuild.

## Indexes the dump relies on

- `AAA_USER(USER_NAME)` — unique or not, it lets the ordered scan avoid a three million row sort in
  temp space, and lets a shard range-scan its own slice.
- `AAA_USER_MAC_ADDRESS(USER_NAME)` and `SERVICE_INSTANCE(USERNAME)` — for the shard range
  predicates pushed into the inline views.
- `BUCKET_INSTANCE(SERVICE_ID)` — for the join back to the active bundle.

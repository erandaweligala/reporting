# Table extracts — MAC_SERVICE_TABLE, PLAN_TO_BUCKET, BUCKET_INSTANCE

Three CSVs, each one row per row of a database table, with the column names the consuming system
uses rather than the ones the schema uses. Two of them are large — a subscriber base of ~3 million
users gives ~3 million services and more bucket instances than that — and this note covers how a
run of that size is kept off the critical path of the rest of the service.

| Report type | Source | Rows, roughly |
| --- | --- | --- |
| `MAC_SERVICE_TABLE` | `SERVICE_INSTANCE`, left-joined to `AAA_USER` for `GROUP_ID` | one per service, ~3 M |
| `PLAN_TO_BUCKET` | `PLAN_TO_BUCKET` | one per plan per bucket, thousands |
| `BUCKET_INSTANCE` | `BUCKET_INSTANCE` | one per bucket per service, > 3 M |

`SERVICE_ID` in the first is `SERVICE_INSTANCE.ID`, which is what `BUCKET_INSTANCE.SERVICE_ID`
points at — so the three files join back together in the consuming system the way the tables do
here.

## Where each column comes from

The headers are the contract, and they come from the sample extracts rather than from the DDL. They
differ from it in ways that must be preserved, so every header line is asserted in
`TableExtractsTest`:

- **`MAC_SERVICE_TABLE.SERVICE_ID`** is `SERVICE_INSTANCE.ID`.
- **`SERVICE_CYCLE_START_DATE` / `SERVICE_CYCLE_END_DATE`** are `CYCLE_START_DATE` / `CYCLE_END_DATE`.
- **`RECURRING_FLAG`** is a `NUMBER(1)` in the schema and `Yes`/`No` in the sample. The mapping is
  done in SQL (`DECODE(si.RECURRING_FLAG, 1, 'Yes', 'No')`) rather than left for the consumer to
  guess at.
- **`GROUP_ID`** is not a `SERVICE_INSTANCE` column. It is the subscriber's group on `AAA_USER` —
  the same column the user data dump reports under that name — which is why this is the one extract
  with a join. `SERVICE_INSTANCE.IS_GROUP` is a flag, not an id, and is deliberately not what is
  reported. **Worth confirming against the schema**, since the sample shows only the value `1`,
  which both columns could produce.
- **`BUCKET_INSTANCE`** reports `USAGE` *before* `UPDATED_AT`, where the DDL orders them the other
  way, and omits `IS_UNLIMITED` entirely even though the table carries it.

Every date and timestamp column is written as `yyyy-MM-dd HH:mm:ss.SSS`, matching the samples. The
`date-format` setting below is the Oracle model that produces it; because it carries fractional
seconds each column is CAST to `TIMESTAMP` before `TO_CHAR` sees it (`TO_CHAR` of a `DATE` with an
`FF` element raises ORA-01821, and the extract does not depend on which of the two each column
happens to be).

## How a run is shaped

```
   Oracle ──── one cursor, TABLE ACCESS FULL, 5 000 rows per round trip
                    │
                    ▼  one reusable row buffer, getString only
            StreamingCsvWriter ──── 1 MB buffer ──── extract.csv
```

**One cursor, not pages.** Rows stream off an open, read-only, forward-only JDBC cursor
(`StreamingRowReader`). Offset pagination — what the paged report framework does — re-walks and
discards everything before each page, so the last page of a three million row extract costs three
million rows of work on its own.

**One sequential pass, and no more than that.** The statement is a projection and nothing else: no
`ORDER BY`, no `GROUP BY`, no filter (`TableExtractSql`). That is the point, not an omission. The
consuming system loads the file into a table, where row order means nothing, while an `ORDER BY`
over a few million rows would either sort in temp space or walk the primary key index and pick rows
up a block at a time — random I/O where the scan was sequential.

**No sharding, deliberately.** The user data dump splits its keyspace into ranges scanned in
parallel; these extracts do not, and the difference is not an oversight. There, each shard also had
an Elasticsearch aggregation to overlap with and a four-way join to plan, so a shard did more than
re-read the same table. Here there is nothing to overlap: N shards over one flat table are N scans
of it, and the database does the same work N times to finish no sooner. One sequential scan is both
the fastest plan Oracle has for this and the lightest thing to ask of a live database.

**Bounded memory, whatever the row count.** Nothing is accumulated: one reusable row buffer, one
buffered writer, and a row that never outlives the callback that writes it. A ten thousand row
extract and a ten million row extract have the same heap profile, so an extract cannot be the
reason the service pauses for a full collection.

**Text conversion in the database.** Timestamps are converted with `TO_CHAR` in SQL and the reader
pulls every column with `getString`. At three million rows the `Timestamp` and `BigDecimal` objects
a typed read would allocate — one per column per row — are what drives the collector, not the I/O.
It also makes the file independent of the JVM's locale and time zone.

**A writer that stays open.** `StreamingCsvWriter` holds one buffered handle for the life of the
extract and formats straight into it, so the file is flushed roughly once per buffer rather than
once per batch, and values reach it exactly as the database produced them. The batch exporter's
habit of rewriting date-looking values into an Excel formula is deliberately not carried over: a
file consumed by another system needs the opposite.

**A bounded footprint on everything else.** An extract runs on the report executor like any other
report, so at most `report.max-concurrent` reports are in flight and the rest queue as `Pending`.
While it runs it holds one Hikari connection and one cursor — no shard pool, no second data source.
`query-timeout-seconds` bounds a scan that has gone wrong rather than letting it hold that
connection indefinitely.

## Running one

Each extract is registered like any other report type and routed down the streaming path by
`UnifiedReportDownloadServiceImpl`. A run is two calls. The first asks for the extract and returns
immediately — at a few million rows the run takes minutes, not the life of an HTTP request:

```
POST /api/report-download/create
userId: <operator>
{ "reportType": "BUCKET_INSTANCE", "classificationLevel": "Confidential" }
```

`format` may be given as `CSV`, but it does not need to be: a request that leaves it out is written
as CSV, because a streaming report is CSV-only and defaulting it to the spreadsheet every other
report gets would fail the run instead. Naming `EXCEL` fails by design — including for
`PLAN_TO_BUCKET`, which is small enough for a spreadsheet, so that the three files that are
loaded together are always the same format.

The second call retrieves the finished file. `id` is the `REPORT_DOWNLOAD` row's id — the create
call does not return it, so it is read back from `POST /api/report-management/filter`, which is
also where the run's status (`Pending` → `Processing` → `Completed`, or `Failed`/`No Records`) is
visible:

```
GET /api/report-download/download?id={id}
```

The response is the extract itself, as `text/csv`, named
`{id}_{REPORT_TYPE}_yyyy_MM_dd_HH_mm_ss.csv` — the file `report.output.directory` holds. Asking for
it before the run finishes answers `4005 Report file not found` along with the status the report is
currently in, so a poll on that endpoint is a legitimate way to wait for it.

## Settings

```yaml
report:
  table-extract:
    jdbc-fetch-size: 5000        # rows Oracle ships per round trip
    query-timeout-seconds: 7200  # an extract that has gone wrong is cancelled, not left running
    csv-buffer-bytes: 1048576    # 1 MB in front of the output file
    date-format: "YYYY-MM-DD HH24:MI:SS.FF3"
```

The settings are shared by all three extracts: they differ only in what they select, and the
defaults that suit the largest are harmless for the smallest — a buffer is allocated per running
extract, not per row.

## Operating notes

- **Run them off-peak.** A full scan of a live OLTP table competes for I/O and buffer cache with
  everything else on it. Nothing here throttles that; the extract is as cheap as a full read of
  those tables can be, and it is still a full read of them.
- **ORA-01555 is the failure to expect.** A scan of a few million rows holds a read-consistent
  snapshot for minutes, and on a table with heavy concurrent DML a small undo retention will not
  cover it. The run fails and is marked `Failed`; the fix is undo retention or a quieter window,
  not a smaller fetch size.
- **The three files are a set.** `MAC_SERVICE_TABLE.SERVICE_ID` joins to
  `BUCKET_INSTANCE.SERVICE_ID`, and each is a separate run against a separate snapshot — a service
  created between two runs appears in one file and not the other. Running them close together
  narrows that window; it does not close it.
- **Indexes.** None are needed. Every extract is read end to end, so the cheapest plan is a full
  scan and no index would improve it. The one join, `SERVICE_INSTANCE` to `AAA_USER` on
  `USER_NAME`, is a hash join over two scans rather than a lookup per row.

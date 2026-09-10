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

Every date and timestamp column is reported as `yyyy-MM-dd HH:mm:ss`, and is displayed that way by
the spreadsheet an extract is checked in — see [Dates](#dates) below for both halves of that. The
samples carried milliseconds; they are no longer reported, because Excel has no date format for a
fractional timestamp and the `="..."` form the columns are written in normalises a fraction away in
any case.

Each column is still CAST to `TIMESTAMP` before `TO_CHAR` sees it, even though the shipped model no
longer carries an `FF` element: `TO_CHAR` of a `DATE` with `FF` raises ORA-01821, so the cast is
what keeps the statement independent both of which of the two a given column is and of whether a
deployment's `date-format` asks for a fraction.

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
once per batch. Values reach it as the database produced them, with one exception the extract asks
for by name — see below.

**A bounded footprint on everything else.** An extract runs on the report executor like any other
report, so at most `report.max-concurrent` reports are in flight and the rest queue as `Pending`.
While it runs it holds one Hikari connection and one cursor — no shard pool, no second data source.
`query-timeout-seconds` bounds a scan that has gone wrong rather than letting it hold that
connection indefinitely.

## Dates

An extract is opened in a spreadsheet before it is loaded anywhere, and Excel does not read a CSV
timestamp as a timestamp: it recognises the text as a date, replaces it with the day number behind
it and does not reliably put a date format on the cell it just converted. What the operator read in
every date column of all three extracts was `46271.07939`.

No format model avoids that — there is no text shape that reads as `yyyy-MM-dd HH:mm:ss` and is
also left alone. So the conversion is stopped instead: a field that begins with `=` is a formula,
and `="2026-08-18 14:31:23"` is the formula whose value is that string. Excel shows it exactly as
written and keeps it as text through a copy or a save. This is what the user data dump does
(`docs/user-data-dump.md`) and what the batch exporter behind the paged reports has always done.

Two things follow from where the form is applied:

- **It is applied per column, not per row.** The flags come from the spec — a column declared with
  `Column.at` is exactly a column the statement renders with `TO_CHAR` — so a column added to an
  extract cannot be rendered as a date by the statement and written as raw text by the writer. A
  number, a name or an empty column is written as it always was; `ExcelSafeTimestamp` returns
  nothing for a value it does not recognise as a timestamp, so a column that has gone wrong is not
  dressed up as a date either.
- **The displayed format is a property of the file, not of the configuration.** The rendered text
  is normalised to `yyyy-MM-dd HH:mm:ss` on the way out, so a deployment whose `date-format` still
  carries `FF3` produces the same cell as one that does not.

`excel-safe-timestamps: false` writes the database's own text back, for a consumer that loads an
extract with something other than a spreadsheet: the `="..."` is a spreadsheet formula, and every
other reader sees the six characters around the timestamp. Nothing else about the file changes with
it.

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
    date-format: "YYYY-MM-DD HH24:MI:SS"  # Oracle model behind every date column
    excel-safe-timestamps: true  # write them as ="..." so Excel displays them
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

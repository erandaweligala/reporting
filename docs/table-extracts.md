# Table extracts — MAC_SERVICE_TABLE, PLAN_TO_BUCKET, BUCKET_INSTANCE

Three CSVs, each one row per row of a database table, with the column names the consuming system
uses rather than the ones the schema uses. Two of them are large — a subscriber base of ~3 million
users gives ~3 million services and more bucket instances than that — and this note covers how a
run of that size is kept off the critical path of the rest of the service.

| Report type | Source | Rows, roughly |
| --- | --- | --- |
| `MAC_SERVICE_TABLE` | `SERVICE_INSTANCE`, left-joined to `AAA_USER` for `GROUP_ID` | one per service, ~3 M |
| `PLAN_TO_BUCKET` | `PLAN_TO_BUCKET` | one per plan per bucket, thousands |
| `BUCKET_INSTANCE` | `BUCKET_INSTANCE`, with `USAGE` read from the CDR documents in Elasticsearch | one per bucket per service, > 3 M |

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
- **`BUCKET_INSTANCE.USAGE`** is not the table's `USAGE` column. It is the total the CDR session
  documents record against that bucket — the figure the user data dump reports as `UTLIZED_QUOTA`,
  for the bucket each row is. See [What `USAGE` is](#what-bucket_instanceusage-is) below.

Every date and timestamp column is reported as `yyyy-MM-dd HH:mm:ss`, and is displayed that way by
the spreadsheet an extract is checked in — see [Dates](#dates) below for both halves of that. The
samples carried milliseconds; they are no longer reported, because Excel has no date format for a
fractional timestamp and the `="..."` form the columns are written in normalises a fraction away in
any case.

Each column is still CAST to `TIMESTAMP` before `TO_CHAR` sees it, even though the shipped model no
longer carries an `FF` element: `TO_CHAR` of a `DATE` with `FF` raises ORA-01821, so the cast is
what keeps the statement independent both of which of the two a given column is and of whether a
deployment's `date-format` asks for a fraction.

## What `BUCKET_INSTANCE.USAGE` is

Everything the CDRs say has been drawn from that bucket instance — the same figure, read the same
way from the same documents, that the user data dump reports as `UTLIZED_QUOTA`. The table's own
`USAGE` column is what AAA has managed to write back against the bucket; the CDR deltas are the
record it is written from, and reporting them is what lets the consuming system reconcile the two.

cdr-service keeps no running total. Each accounting event carries a cumulative `totalUsage`, and
what the session document stores is the *difference* from the event before it, tagged with the
`bucketId` it was drawn from and the `serviceId` of the bundle it was charged to. So a total is
something this extract sums, over every daily index the cluster still holds — `radius-sessions-*`,
today's among them, because a bucket taken out this morning has drawn all it has drawn from the
index cdr-service is writing into. `usage.lookback-days` bounds that scan for a cluster that keeps
years of indices.

Each row asks for its own bucket's share by the pair it already carries: `BUCKET_ID` and
`SERVICE_ID`. Both are needed. A bucket id names the *plan's* bucket rather than one instance of
it — a subscriber on a recurring plan draws on the same id cycle after cycle — so a total keyed on
it alone would report a year of renewals against every one of that subscriber's rows.
`usage.scope-to-service` is what splits it by the `serviceId` on each session instance, which is
what `BUCKET_INSTANCE.SERVICE_ID` points at.

**How the two sides meet.** cdr-service groups its documents by `userName`, so a bucket's usage is
reached through the subscriber holding its service rather than by the bucket's own id — which is
the one thing that makes this extract's run differ from the other two:

```
   Oracle ─── BUCKET_INSTANCE ⟕ SERVICE_INSTANCE, ORDER BY si.USERNAME
                    │
                    ├── merge-joined in step, one user's figures at a time
                    │
   ES     ─── composite aggregation over radius-sessions-*, by userName
                    │
                    ▼
            StreamingCsvWriter ──── 1 MB buffer ──── extract.csv
```

Both sides are read in username order and consumed in step, so nothing is held but the row being
written and the aggregation page being drained: heap use is flat at three million rows or thirty,
and the two scans overlap. The alternatives are what that buys. A lookup per row would be millions
of Elasticsearch round trips; a map of every bucket's usage, built up front, would cost a full
aggregation scan and hundreds of megabytes before the first row could be written. The orderings
have to agree for the merge to work, so the extract's session forces `NLS_SORT=BINARY` and
`Utf8Order` gives Java the same ordering — the same arrangement the dump uses, for the same reason.

The costs, which the other two extracts do not pay: a join to `SERVICE_INSTANCE`, an `ORDER BY`
that is a sort of a few million rows in temp space unless the optimizer can walk
`SERVICE_INSTANCE(USERNAME)` and nested-loop into `BUCKET_INSTANCE(SERVICE_ID)`, and one
Elasticsearch aggregation over the whole username keyspace. The join is a LEFT join and the
ordering puts its misses last: a bucket whose `SERVICE_ID` resolves to no service instance is still
a row of the table and still a row of the extract, with an empty `USAGE`.

### Reading an empty `USAGE` against a 0

The two look alike in a spreadsheet and mean different things — the same distinction the dump
documents for `UTLIZED_QUOTA`, because it is the same lookup:

**Empty** is a row nothing was found for: a subscriber with no session document anywhere in the
scan, or a row that carries nothing to look one up by — a `SERVICE_ID` that resolves to no service
instance, or no `BUCKET_ID`.

**0** is a subscriber who *was* found, holding a bucket none of their session instances name. One
such row is a bucket nothing has been drawn from — a bandwidth bucket, which no CDR keys usage on,
is reported as 0 for exactly that reason. A whole file of them is the tell that the id the CDR keys
usage on is not `BUCKET_INSTANCE.BUCKET_ID`.

Neither can be told from the other in the file, so the run counts both and says so when it ends:

```
BUCKET_INSTANCE took USAGE from the CDR session documents for 3182044 row(s): 118 found no CDR
usage at all, whose USAGE is empty, 902 hold a bucket their CDRs never name, whose USAGE is 0, and
3 carry nothing to look up — a service that no longer resolves, or no bucket id — and are empty as
well
BUCKET_INSTANCE took USAGE from the row's own bundle for 3181021 of 3181024 row(s) with both a
bucket and CDR usage, and from the bucket's total across bundles for the rest
```

A run reporting the second line's fallback for most of its rows is a deployment whose CDRs do not
carry `SERVICE_INSTANCE.ID` in `serviceId`; `scope-to-service: false` is the honest setting until
they do, and it reports the bucket's total across bundles for every row rather than for some.

Two caps bound what one subscriber can be asked for: `usage.buckets-per-user` (20) and
`usage.services-per-user` (10). A subscriber whose CDRs name more buckets than that has the rest
reported as 0, so a deployment keeping many cycles of buckets per subscriber should raise them
rather than read the zeroes as quiet buckets.

### When the column falls back to the table

`usage-from-cdr: false` reports `BUCKET_INSTANCE.USAGE` as the table holds it, and the extract is
then the single unordered scan the other two are — no join, no `ORDER BY`, no Elasticsearch. A run
falls back to that by itself in two cases, because in both the CDR figure would be quietly wrong
rather than absent:

- `report.user-dump.usage.enabled: false`, which would leave the column empty for every row.
- `report.user-dump.usage.nested: false`. Elasticsearch flattens a `sessionInstances` array that is
  not mapped as nested, so usage cannot be attributed to one bucket at all; the only figure
  available is the subscriber's whole usage, and writing that against each of their buckets would
  read as a total several times over. Fixing the mapping is what gets the column back.

Which of the two figures a deployment reports is in the startup log and nowhere in the file, so it
is a deployment-wide setting rather than something a request can ask for.

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

The one extract that does carry an ordering is `BUCKET_INSTANCE` reading its `USAGE` from the CDR
documents, and it carries one for the only reason that pays for itself: something outside the
database is being read in step with the scan, and an ordering is what keeps that read from
buffering either side. That variant is a spec of its own (`TableExtracts.BUCKET_INSTANCE_FROM_CDR`)
rather than a flag on this one, so the plain extract stays plain — see
[What `BUCKET_INSTANCE.USAGE` is](#what-bucket_instanceusage-is).

**No sharding, deliberately.** The user data dump splits its keyspace into ranges scanned in
parallel; these extracts do not, and the difference is not an oversight. There, each shard is also
a slice of the dump's four-way join and of its own Elasticsearch aggregation, and shard 0 writes
into the output file while the rest write parts that are concatenated at the end. Here the scan is
one flat table and one output file: N shards over it are N scans, and the database does the same
work N times to finish no sooner. The bucket extract's aggregation does not change that either — it
is read in step with the scan rather than raced against it, so sharding it would buy a second scan
of the table to overlap a second aggregation with, not a faster run.

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
While it runs it holds one Hikari connection and one cursor — no shard pool, no second data source —
and, for the bucket extract's usage column, one Elasticsearch aggregation paged through `after_key`
with no scroll context left open on the cluster. Nothing is ever written to Elasticsearch.
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
    usage-from-cdr: true         # BUCKET_INSTANCE.USAGE is the CDR total, not the table's column
```

The settings are shared by all three extracts — except the last, which is one extract's one column:
they differ only in what they select, and the defaults that suit the largest are harmless for the
smallest, since a buffer is allocated per running extract and not per row.

`usage-from-cdr` decides where `BUCKET_INSTANCE.USAGE` is read from and so what it means; the
figure itself is configured once, with the dump's, under `report.user-dump.usage`:

```yaml
report:
  user-dump:
    timezone: "${TZ:UTC}"        # the zone the CDR daily indices are named in
    usage:
      enabled: true              # off leaves the extract reporting the table's own counter
      index: radius-sessions     # cdr-service `sessions-data`
      page-size: 2000            # usernames per aggregation page
      lookback-days: 0           # 0 = every daily index the cluster holds; else N days ending today
      buckets-per-user: 20       # distinct buckets counted for one subscriber
      scope-to-service: true     # each row reports its own bundle's share of the bucket
      services-per-user: 10      # bundles weighed against one of a subscriber's buckets
      nested: true               # off leaves the extract reporting the table's own counter
      instances-path: sessionInstances
```

Two reports read that figure and there is one definition of it, so a change here moves
`UTLIZED_QUOTA` and `BUCKET_INSTANCE.USAGE` together — which is the point: they are the same number
asked for by different rows. `docs/user-data-dump.md` documents each setting in full.

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
- **Elasticsearch is on the path of one extract.** A cluster that cannot be reached fails the
  `BUCKET_INSTANCE` run rather than emptying its `USAGE` column — the same choice the dump makes
  for `UTLIZED_QUOTA`, and for the same reason: a file of empty usage reads as a subscriber base
  that has drawn nothing. `usage-from-cdr: false` is what takes the dependency away.
- **Indexes.** None are needed for `MAC_SERVICE_TABLE` or `PLAN_TO_BUCKET`: each is read end to
  end, so the cheapest plan is a full scan and no index would improve it, and the one join,
  `SERVICE_INSTANCE` to `AAA_USER` on `USER_NAME`, is a hash join over two scans rather than a
  lookup per row. The bucket extract reading CDR usage is the exception —
  `SERVICE_INSTANCE(USERNAME)` and `BUCKET_INSTANCE(SERVICE_ID)` are what can give Oracle an
  ordered plan for it instead of a sort of every bucket instance in temp space. Both already exist
  for the user data dump.
- **Temp space, for that extract only.** If the optimizer does choose the sort, it sorts a few
  million rows; a `TEMP` tablespace sized for the rest of this service's work is not automatically
  sized for that. `usage-from-cdr: false` is the setting that takes the ordering away again.

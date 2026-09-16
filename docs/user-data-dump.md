# USER_DATA_DUMP — the D-1 subscriber base extract

A single CSV holding one row per AAA user (~3 million), describing the bundle they held on the
previous day and how much of it they have used. This note covers the shape of the run, the choices
that make it survive that row count, and the settings an operator needs.

## Where each column comes from

| Columns | Source |
| --- | --- |
| `USER_ID`, and every column not listed below | `AAA_USER` |
| `MAC_ADDRESS`, `ORIGINAL_MAC_ADDRESS` | `AAA_USER_MAC_ADDRESS`, one cursor row each, comma-joined per user as the rows are read (see below) |
| `BUNDLE_ACTIVATION_DATE`, `BUNDLE_NAME`, `BUNDLE_DEACTIVATION_DATE` | `SERVICE_INSTANCE` |
| `PLAN_BANDWIDTH`, `QUOTA` | `BUCKET_INSTANCE` — `RULE` of the bandwidth bucket, or of whichever bucket the bundle holds one on; `INITIAL_BALANCE` of the quota bucket |
| `SLMN` | the username — `AAA_USER` has no `SLMN` column |
| `NOTIFICATION_TEMPLATES` | `AAA_USER.TEMPLATE_ID` — there is no column of that name either |
| `NAS_IP_ADDRESS` | Elasticsearch: the NAS the user's D-1 sessions were anchored to, or — where the D-1 index holds no session of theirs — the one their most recent session names |
| `UTLIZED_QUOTA` | Elasticsearch: everything the reported bundle has drawn from its quota bucket, summed over every CDR index the cluster holds — today's included |
| `CUSTOMER_ACTIVATION_DATE` | `AAA_USER.ACTIVATION_DATE` |

Column order is part of the contract with the consuming system and is asserted in
`UserDataDumpReportDefinitionTest`. `UTLIZED_QUOTA` is spelled the way the consumer spells it.

Three of those columns have no `AAA_USER` column of that name behind them. Two are stand-ins the
dump statement binds in the column's place, each aliased to the dump column it fills so the select
list can still be read against the header: `SLMN` carries the username, and
`NOTIFICATION_TEMPLATES` the `TEMPLATE_ID` that names the templates the user's notifications are
sent from. Selecting a `NOTIFICATION_TEMPLATES` column — which is what the
statement did at first — costs the whole dump rather than the one column: Oracle rejects the
statement with **ORA-00904 (invalid identifier)** on every shard of every run, the same way it
rejects a construct its parser does not know. `NAS_IP_ADDRESS` has no stand-in worth binding — it
is the `nasIpAddress` cdr-service records on the session document from the CDR every accounting
event carries — under whichever of the two spellings that index's mapping makes aggregatable, which
the dump resolves rather than assumes (see below) — so the statement selects nothing in that
position and
`UserDataDumpReportDefinition` splices the value in as it lays the row out, from the same
Elasticsearch pass that already produces `UTLIZED_QUOTA` rather than from a lookup of its own. A
user whose sessions named more than one NAS that day is reported under the one most of them used;
a user the whole scan holds no session of gets an empty column.

Every timestamp is written as `yyyy-MM-dd HH:mm:ss` — the `date-format` below is the Oracle model
that produces it. Each column is still CAST to `TIMESTAMP` before `TO_CHAR` sees it, so the
statement does not depend on which of `DATE` and `TIMESTAMP` each column happens to be and so the
model can carry fractional seconds without raising ORA-01821 (`TO_CHAR` of a `DATE` with an `FF`
element does).

**The date columns are written as `="2026-09-06 01:54:19"`, so that Excel displays them.** A CSV
field carries no type, so Excel guesses one for every cell it opens, and a timestamp is the guess it
gets wrong: it reads the text as a date, replaces it with the day number behind it —
`46271.07939` — and shows the number. It is the same guess that turns an SLMN into `5.1E+09`; a
date column is only where it is impossible to ignore.

The first attempt at this was to feed the guess a better shape. The sample extract writes
`yyyy-MM-dd HH:mm:ss.SSS` and this dump followed it, so the milliseconds were dropped from
`date-format` on the reasoning that Excel has no date format for a fractional timestamp. The date
columns still came out as serial numbers. There is no text shape that reads as `yyyy-MM-dd
HH:mm:ss` and is also left alone, so what the dump does now is stop the guess instead: a field
beginning with `=` is a formula, and `="2026-09-06 01:54:19"` is the formula whose value is that
string. Excel shows it exactly as written and never converts it. This is the same thing the batch
exporter behind the paged reports (`GenericCsvExporter`) has always done; the streaming reports are
what did not have it.

Two things follow from doing it in Java rather than in the format model. The rendered text is
normalised to `yyyy-MM-dd HH:mm:ss` on the way out — a fractional seconds field is dropped — so the
displayed format is a property of the file rather than of whichever model a deployment happens to
carry: a `date-format` still ending in `.FF3` produces the same cell. And it is a switch, not a
rewrite of the statement: `excel-safe-timestamps: false` writes the database's own text back for a
consumer that reads the dump with something other than a spreadsheet, which is what it should be
set to if that consumer chokes on the six characters around each timestamp. Nothing else about the
file changes with it, and only the five columns the statement renders with `TO_CHAR` are affected —
`CYCLE_DATE` is selected as it stands and is left alone.

`QUOTA` is left **empty** for a bundle whose data bucket is unlimited: the consumer reads a blank
there as "no cap", so `UserDumpSql` excludes unlimited buckets from the pivot rather than writing a
label into the column.

## Which bucket `PLAN_BANDWIDTH` comes from

The bandwidth bucket's `RULE` where the bundle has such a bucket, and the `RULE` its own buckets do
carry where it has not.

The column reached the file **empty on every row** while it was only ever the `RULE` of a bucket
whose `BUCKET_TYPE` equalled `bandwidth-bucket-type` exactly, and the rows it was reported from all
held a rate rule. A pivot that matches no row is not an error anywhere in the run — it is a `NULL`,
so an empty column is all it produces, and it produces it for every subscriber at once. Two ways to
match nothing were both live:

- **The type is spelled differently.** `BUCKET_TYPE` carries whatever case and padding the plan was
  defined with, and `'Bandwidth' = 'BANDWIDTH'` is false in Oracle. The `USAGE` column of the
  `BUCKET_INSTANCE` extract has compared the same setting with `equalsIgnoreCase` for as long as it
  has read it; the dump compared it with `=`. Both sides of the comparison are now normalised —
  `UPPER(TRIM(BUCKET_TYPE))` against a trimmed, upper-cased bind — so the spelling no longer decides
  whether a bucket is found.
- **There is no bandwidth bucket at all.** A deployment that keeps one bucket per bundle carries the
  rate rule on that one bucket, whatever its type, and no value of `bandwidth-bucket-type` reaches
  it. The pivot therefore falls back: where no bucket of the configured type answers, the `RULE` the
  bundle's buckets do carry is reported. The fallback is an aggregate over the same group, so it can
  only ever see the buckets of the service instance being reported — the join to `svc` has already
  pruned it to that bundle — and a deployment with a real bandwidth bucket never reaches it, because
  its pivot matches.

An empty `PLAN_BANDWIDTH` therefore no longer means the bandwidth bucket is named something else. It
means the statement found the bundle no bucket carrying a `RULE`, which on a subscriber base that
has them is `BUCKET_INSTANCE` holding no row for the `SERVICE_INSTANCE` the dump picked. The rows are
counted and the shard says so, because the file cannot be read for it:

```
Shard [ .. 4) wrote an empty PLAN_BANDWIDTH for 0 of 762143 user(s) ...
```

The line is logged only when the count is not zero.

Leaving `bandwidth-bucket-type` unset is a supported setting and means "whichever bucket holds the
rule": it is bound as a value Oracle matches nothing against, so every row takes the fallback.
`quota-bucket-type` has no such fallback — an arbitrary bucket's `INITIAL_BALANCE` is not this
bundle's allowance — so an unset quota type reports an empty `QUOTA`, and `UTLIZED_QUOTA` beside it
degrades to the subscriber's total across buckets, as it always has when no quota bucket is named.

## What `UTLIZED_QUOTA` is the total of

It is read next to `QUOTA`, which is the bucket's whole allowance, so it has to be the whole of
what the bundle has drawn from that bucket. It used to be one day of it — the aggregation named the
D-1 index and nothing else, so a subscriber a fortnight into a 100 GB bundle was reported as having
used whatever they happened to move on Tuesday. Two things make it a total instead.

**Every index the cluster holds, today's included.** cdr-service keeps no running total to read:
each accounting event it consumes carries a cumulative `totalUsage`, and what it stores on the
session document is the *difference* from the event before it, tagged with the `bucketId` it was
drawn from. A total is therefore something the dump sums, and the days it sums over are every daily
index the cluster still holds — `radius-sessions-*`, nothing struck out of it.

Widening the scan to D-1 and no further was the first attempt at that, and it kept a guarantee that
turned out to cost the column: the days after the reported one, today's hot index among them, were
excluded by name so that a dump never read what cdr-service was writing into. A subscriber whose
bundle was taken out this morning has drawn *all* of what they have drawn from that index. Excluded,
they are not a row reporting zero — they are a user the aggregation never returns at all, so both
Elasticsearch-filled columns come out **empty**:

```
BUNDLE_NAME          QUOTA   UTLIZED_QUOTA   NAS_IP_ADDRESS
Unlimited 50 (90 D)                                          ← usage recorded today, none reported
```

Aggregating a live index costs the cluster a search, which is what every other day of the scan
costs it too; a total read next to `QUOTA` is worth more than the guarantee was. The reported day
still bounds what it should — which bundle is reported, and which day's `NAS_IP_ADDRESS` sits
beside the total. `usage.lookback-days` bounds the scan to a fixed number of days, ending today,
for a cluster that keeps years of them.

One thing a wider scan does depend on that a single-index one did not: every index it reads has to
map `sessionInstances` the same way. A `nested` aggregation over an index where that field is a
plain object is rejected by the cluster, so an index old enough to predate cdr-service's mapping
template fails the run rather than being skipped — loudly, in the shard's exception, naming the
index. `usage.lookback-days` set to cover only the days since the mapping settled is the fix that
needs no reindex; repairing the old index (cdr-service's README has the procedure) is the one that
gets the history back.

**The bundle's own share, not every bundle's.** `BUCKET_ID` names the plan's bucket rather than one
instance of it — `bkt.QUOTA_BUCKET_ID` is the same string cycle after cycle for a subscriber on a
recurring plan — so a total keyed on it alone would report a year of renewals against this month's
`QUOTA`. What separates them is the `serviceId` cdr-service records beside `bucketId` on each
session instance, which is the `SERVICE_INSTANCE` the usage was charged to; the dump statement now
selects `svc.ID` alongside the bucket id for exactly that reason, and the aggregation splits each
bucket's total by service so the row can ask for its own bundle's share.

Both figures are kept, and the unsplit one is what the dump falls back to — but only where there is
no split to read. If the CDR's `serviceId` turns out not to be `SERVICE_INSTANCE.ID`, the bucket
comes back with no bundles named against it at all and every row reports the bucket's total across
bundles — the same figure `scope-to-service: false` asks for — rather than a column of zeroes that
would read as a subscriber base that has used nothing. The same holds for a bucket more bundles
touched than `usage.services-per-user` has room for: a truncated split cannot tell a bundle that
drew nothing from one the aggregation left out.

Where the split *is* the whole of who drew on the bucket and the reported bundle is not in it, the
bundle drew nothing and `UTLIZED_QUOTA` is `0`. Reporting the bucket's total there would be the very
thing `scope-to-service` exists to prevent — every past cycle's usage against this cycle's `QUOTA` —
and it is what a bundle taken out since cdr-service's last delta used to report.

The fallback is invisible in the file, so each shard logs how many of its rows were attributed:

```
Shard [f .. s) took UTLIZED_QUOTA from the reported bundle's own usage for 0 of 748210 user(s)
holding a quota bucket; the rest drew nothing the CDRs attribute to that bundle
```

A shard reporting 0 there is a deployment whose CDRs do not key usage the way the dump assumes, and
`scope-to-service: false` is the honest setting until they do.

`BUCKET_INSTANCE.USAGE` reads this same figure and can ask for its bucket by `BUCKET_INSTANCE.ID` as
well as by `BUCKET_ID`, because it is a row of that table and holds both. The dump reaches its
bucket through the plan and has only `BUCKET_ID` to offer — see `docs/table-extracts.md`.

`NAS_IP_ADDRESS` comes out of the same pass and is **not** a lifetime figure — it is still the NAS
the reported day's sessions were anchored to, read inside a filter of its own, so widening the usage
around it left it where it was. What that filter needed afterwards is the next section: the index a
session document sits in is the day the session *started*, which is not the day it covered.

## Which field `NAS_IP_ADDRESS` is read from

The dump asks the reported day's index, once per run, rather than assuming. It has to, because
assuming wrong is invisible: **a terms aggregation on a field the mapping does not have answers
with no terms and no error**, which the dump writes out as an empty `NAS_IP_ADDRESS` for every
user of a run that otherwise succeeds — next to a `UTLIZED_QUOTA` that is perfectly correct,
because the usage join reads `userName.keyword` and the two ids under `sessionInstances`, all of
which the mapping does have. That is what the column was doing.

The two spellings are one mapping decision seen from either side:

| cdr-service maps `nasIpAddress` as | the aggregatable field is |
| --- | --- |
| `text` (what a dynamic mapping produces) | `nasIpAddress.keyword` — the sub-field |
| `keyword`, or `ip` — what a template written for an address does | `nasIpAddress` itself |

`usage.nas-ip-field` names the one to try first, and the same name with `.keyword` taken off (or,
for a name configured without it, added on) is tried next. A field capabilities call against the
reported day's index — one request, shared by every shard of the run — says which of them that
index can actually aggregate, and the log says which was taken:

```
NAS_IP_ADDRESS will be read from nasIpAddress: nasIpAddress.keyword is not an aggregatable field
of radius-sessions-2026.09.14, which maps the address without the .keyword sub-field a text
mapping would add
```

So the setting only needs overriding where cdr-service records the address under a different name
altogether. Where neither spelling is there, the run says so and names which half is missing — the
index or the field — because they need different fixes:

```
NAS_IP_ADDRESS will be empty for every user: none of [nasIpAddress.keyword, nasIpAddress] is an
aggregatable field of radius-sessions-2026.09.14. No index of that name answered, so the reported
day's sessions are not under it — check report.user-dump.usage.index and report.user-dump.timezone
against the daily indices cdr-service writes
```

A cluster that will not answer the lookup at all does not fail the dump: the configured spelling
is used, exactly as it was before the lookup existed, and the log says the column may be empty.

Each shard then counts what it actually wrote, so the column can never go quiet again:

```
Shard [f .. s) wrote an empty NAS_IP_ADDRESS for 12 of 748210 user(s); of the rest, 651767 were
anchored to a NAS the reported day's own sessions name and 96431 to one their most recent session
names, the reported day's index holding no session of theirs
```

A handful of users in the first figure is a subscriber base with quiet days in it. All of them is
the field, the filter, or the day's index — and it is a separate count from the usage one above
because the two columns fail apart: `NAS_IP_ADDRESS` is read through a filter and a field of its
own, so it can be empty on every row of a run whose `UTLIZED_QUOTA` is right. The second figure is
the fallback of the next section; every row of a run taking it is a reported day with no index of
its own, rather than a day being read.

The same figure, asked for by the same pair, is what the `BUCKET_INSTANCE` extract reports as
`USAGE` — one row per bucket instance rather than one per user, so it asks for every bucket rather
than only the one behind `QUOTA`. It reads this section's settings rather than carrying its own, so
there is one definition of what the number is; see `docs/table-extracts.md`. Its rows do not need
the NAS address, so that sub-aggregation is left out of its request.

That includes the fallbacks, which is what lets the two files be read against each other. Where
`usage.nested` is off there is no split for either report to read, so both report the subscriber's
whole CDR total: this one as their `UTLIZED_QUOTA`, and the extract against the one bucket of
theirs that total belongs to — their quota bucket — with their other buckets reporting `0`. The
extract used to give the column up to `BUCKET_INSTANCE.USAGE`, the counter AAA writes back, which
is what left the two columns showing unrelated figures for the same subscriber.

## Which day `NAS_IP_ADDRESS` is read from

The reported day's index where that index holds a session of the user's, and the newest index that
does where it holds none.

The fallback is there because **a session document is filed under the day its session started**,
not under the days that session covered. `radius-sessions-2026.09.15` is not "the sessions of the
15th": it is the sessions *opened* on the 15th. So two ordinary subscribers have no document in it
at all —

- one whose line came up this morning, whose session is in today's index, and
- one whose line came up last week and has not dropped since, whose session is in last week's —
  which on an always-on FTTH base is most of it.

— and a column read only through `_index = radius-sessions-2026.09.15` was empty for both, beside a
`UTLIZED_QUOTA` reporting that very session's usage, because the totals are summed over every index
in the scan. That is the row this was reported on:

```
USER_NAME       QUOTA        UTLIZED_QUOTA   NAS_IP_ADDRESS
taiwowilliams   Unlimited    80                               ← session up since 05:55 today
```

The address was in the scan the whole time, one index over. So where the reported day's index names
no address for a user, the dump reads the newest daily index that does: a terms aggregation on
`_index` ordered by key descending and kept to one bucket, with the same NAS terms under it. The
names sort by date because they are written `yyyy.MM.dd`, and ordering by the key rather than by
doc count is what makes one bucket exact — each shard holds one index's documents and answers with
its own name, so the merge keeps the newest of them.

Newest, not busiest: the address reported is the one the subscriber was **last** anchored to, which
is what an operator reading the column wants, rather than whichever NAS they used most across the
years of history the scan may cover — that last figure is precisely what the reported day's filter
exists to keep out of the column, and it stays out.

The reported day still wins wherever it names an address, so no row that had one changes. A user
the whole scan holds no session of still gets an empty column, as does one whose sessions named no
NAS. The rows that took the fallback are counted in the shard line above, because nothing in the
file separates them, and `usage.nas-ip-fallback-to-latest-day: false` restores the reported day as
the only source for a consumer that needs the column to be that day's or nothing.

## Reading an empty or zero `UTLIZED_QUOTA`

The two look alike in a spreadsheet and mean different things, and neither can be told from the
other in the file — so each shard counts them:

```
Shard [f .. s) found no CDR usage at all for 12 of 748210 user(s) — UTLIZED_QUOTA and
NAS_IP_ADDRESS are empty for those — and found 3 more holding a quota bucket their CDRs never
name, whose UTLIZED_QUOTA is 0
```

**Empty** is a user the aggregation never returned: no session document under that username in any
index the scan covered. `NAS_IP_ADDRESS` is empty beside it, which is what tells the two apart at a
glance. One such user is a subscriber who has never held a session; a shard reporting most of its
users here is reporting that `userName` on the CDR documents is not `AAA_USER.USER_NAME` — or that
the scan is not covering the days the sessions are in.

**0** is a user who was found, holding a quota bucket that none of their session instances name.
One such user has not touched that bucket. A shard reporting most of its users here is reporting
that the id the CDR keys usage on is not the `BUCKET_INSTANCE.BUCKET_ID` the dump statement reads —
a `bucketId` that looks like a row id rather than a plan's bucket name is the tell — and the figure
is a zero only because the lookup missed, not because nothing was drawn.

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

**One query, not four.** The active bundle, its buckets and the MAC addresses all come off the one
dump statement (`UserDumpSql`) — the first two as pre-aggregated inline views, the third as a plain
join. Fetching them per user would be three round trips per row, around nine million on a three
million row dump. Each view collapses its table in one pass and is hash-joined; the bucket view is
joined to the bundle view so the optimizer prunes it to the shard's own services rather than
scanning `BUCKET_INSTANCE` whole.

**MAC lists joined in Java, because `LISTAGG` is what the server rejects.** Every run of this
report failed with **ORA-00907 (missing right parenthesis)** for as long as the statement collapsed
the MAC addresses with `LISTAGG`. The first attempt at it blamed `LISTAGG`'s own
`ON OVERFLOW TRUNCATE` clause, which is 12.2 and later; removing the clause changed nothing,
because the server rejects `LISTAGG(...) WITHIN GROUP (...)` itself — reaching the `WITHIN` where
it expects the subquery's closing bracket produces exactly the same error. So the aggregate is gone
from the statement: `AAA_USER_MAC_ADDRESS` is joined row for row and the cursor hands out one row
per MAC address, which `UserDataDumpReportDefinition` joins up as it reads them. The shard bounds
go inside that join's `ON` clause — in the `WHERE` clause they would turn the outer join into an
inner one and drop every user who holds no MAC address.

That costs the extra rows the fan-out produces (a user with three addresses arrives three times)
and buys three things: nothing in the statement is newer than Oracle 9i, the `VARCHAR2` limit that
made `LISTAGG` raise ORA-01489 past 4 000 bytes no longer applies at all, and the byte budget that
guarded against it is now spent in Java. The budget itself is kept, and kept the same way —
each address is charged the wider of the row's two values, so both lists are cut at the same
address and `MAC_ADDRESS` and `ORIGINAL_MAC_ADDRESS` stay row-aligned. Anything reintroducing a
version-dependent construct into this statement fails the whole dump, not one column; when a
statement is rejected, `StreamingRowReader` now logs the statement text alongside the ORA error so
the next one can be diagnosed from the log rather than guessed at.

**Aliases go outside the `CAST`, never into the column.** The same ORA-00907 came back a second
time, from something plainer than a version-dependent construct: a column alias written into the
operand of a cast — `CAST(u.CREATED_DATE as CUSTOMER_ACTIVATION_DATE AS TIMESTAMP)` — puts a second
`AS` where the cast's closing bracket belongs, so Oracle rejects the statement before it looks at
anything else, on every shard of every run. The dump's five rendered timestamps do carry the dump's
column names (`CREATED_DATE`, `UPDATED_DATE`, `CUSTOMER_ACTIVATION_DATE`, `BUNDLE_ACTIVATION_DATE`,
`BUNDLE_DEACTIVATION_DATE`), which is worth having in the logged statement when two of them render
the same `AAA_USER.CREATED_DATE` into different columns — but the name is given to the finished
`TO_CHAR`, by an argument, not spliced into the column. `OracleText` now refuses a column that
carries an alias, an expression, or anything else that is not a plain column reference, so a
mistake of that shape fails in Java with the fragment named rather than at the database with only
an ORA number to go on.

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
from a composite aggregation over every `radius-sessions-yyyy.MM.dd` index — one number
per user per bucket, and one more per bundle that drew on it, over the wire, paged through
`after_key`, with no scroll context left open on the cluster. Widening the index expression does
not widen the response: a terms aggregation returns the buckets and services that exist, which for
a real subscriber is a handful whatever the window.

The two orderings have to agree for the merge to work. Oracle's binary collation and
Elasticsearch's keyword ordering both compare UTF-8 bytes: the dump session forces
`NLS_SORT=BINARY`, and `Utf8Order` gives Java the same ordering (`String.compareTo` disagrees for
code points above U+FFFF).

**A writer that stays open.** `StreamingCsvWriter` holds one buffered handle for the life of the
dump and formats straight into it. The batch exporter rebuilds a multi-megabyte String per batch
and reopens the file for every append — invisible at a few thousand rows, dominant at three
million. Values are written exactly as the database produced them, with the one exception the
definition asks for by column: the five timestamp columns go through `ExcelSafeTimestamp`, because
a spreadsheet does not read a CSV timestamp as one — see **The date columns** above. Every other
column reaches the file as the database produced it.

**Shards, not threads over rows.** The username keyspace is cut into contiguous ranges
(`UserDumpShardPlanner`) that both Oracle and Elasticsearch can filter on, so each shard is a
genuinely independent scan on both sides. Shard 0 writes into the output file itself — it already
carries the header — and later shards write parts that are appended with `FileChannel.transferTo`,
inside the file system rather than through the heap. Shards run on their own pool
(`userDumpExecutor`) so a dump cannot starve the other reports of their slots.

Elasticsearch is only aggregated, never queried for hits — a search over the daily indices,
today's included, with no scroll context and no write of any kind.

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
    date-format: "YYYY-MM-DD HH24:MI:SS"
    excel-safe-timestamps: true  # write the date columns as ="..." so Excel displays them
    bandwidth-bucket-type: BANDWIDTH
    quota-bucket-type: DATA
    usage:
      enabled: true
      index: radius-sessions     # cdr-service `sessions-data`
      page-size: 2000
      lookback-days: 0           # 0 = every daily index the cluster holds; else N days ending today
      buckets-per-user: 20
      scope-to-service: true     # UTLIZED_QUOTA is the reported bundle's share of its bucket
      services-per-user: 10      # bundles weighed against one of a user's buckets
      nas-addresses-per-user: 5  # distinct NAS addresses weighed before NAS_IP_ADDRESS is picked
      nas-ip-field: nasIpAddress.keyword
      nas-ip-fallback-to-latest-day: true  # fill the column from the user's most recent session
                                           # where the reported day's index holds none of theirs
      nested: true
      instances-path: sessionInstances
```

`usage.enabled: false` leaves both Elasticsearch-filled columns — `UTLIZED_QUOTA` and
`NAS_IP_ADDRESS` — empty rather than failing the run. It also takes the CDR figure away from the
`BUCKET_INSTANCE` extract, which reports the table's own `USAGE` column instead of an empty one;
the same goes for `usage.nested: false`, since a figure that cannot be attributed to a bucket is
not a bucket's usage.

`usage.lookback-days` and `usage.scope-to-service` are the two knobs behind the total — see
**What `UTLIZED_QUOTA` is the total of** above. A positive `lookback-days` names that many daily
indices ending with today's and adds one index name to the search per day it covers, so a window of
several hundred days belongs to the wildcard (0) rather than to a list; `scope-to-service: false`
is what a deployment whose CDRs do not carry `SERVICE_INSTANCE.ID` should be set to, and the
per-shard log line says whether it is one.

`usage.nas-ip-field` and `usage.nas-ip-fallback-to-latest-day` are the two behind the address —
see **Which field** and **Which day `NAS_IP_ADDRESS` is read from** above. The field only needs
overriding where cdr-service records the address under a different name altogether, since both
spellings of the configured one are tried against the day's mapping; the fallback wants turning off
only for a consumer that needs the column to be the reported day's or nothing, and the per-shard log
line says how many rows it filled.

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
  the per-MAC table can produce. `AAA_USER_MAC_ADDRESS.ID` is what orders a user's addresses within
  their row.
- `BUCKET_INSTANCE.BUCKET_TYPE` distinguishes the bandwidth bucket from the data bucket, and the
  bandwidth bucket's `RULE` — the rate rule it is policed by — is what the dump reports as
  `PLAN_BANDWIDTH` (`FTTH_50Mbps` in the sample). `BUCKET_ID` names the bucket that rule belongs
  to and is read only as the key the `UTLIZED_QUOTA` lookup is asked with. Both type values are
  configurable above, and neither is matched literally — see **Which bucket `PLAN_BANDWIDTH` comes
  from** below. `IS_UNLIMITED = 1` marks a bucket with no cap, and such a bucket is reported as an
  empty `QUOTA`.
- `CYCLE_DATE` is not a timestamp. It is the one date-named column the dump does not run through
  `TO_CHAR`, so if it turns out to be an Oracle `DATE` it will come back in the session's NLS
  format rather than the dump's — it is blank in the sample extract, so the schema is the only
  place to settle it.
- `BUNDLE_ACTIVATION_DATE` is `SERVICE_INSTANCE.SERVICE_START_DATE` and `BUNDLE_DEACTIVATION_DATE`
  is `EXPIRY_DATE`; where a user has held several bundles, the one active on D-1 is reported, most
  recent first.
- `AAA_USER` has no `SLMN`, `NAS_IP_ADDRESS`, `CUSTOMER_ACTIVATION_DATE` or
  `NOTIFICATION_TEMPLATES` column, which is why those four are filled as described at the top. If a
  column for any of them is added later, selecting it is a one-line change in `UserDumpSql` — plus,
  for `NAS_IP_ADDRESS`, the column positions in `UserDataDumpReportDefinition`, since that one
  shifts the result set.
- `AAA_USER.TEMPLATE_ID` is what the dump reports as `NOTIFICATION_TEMPLATES`. It is the one
  notification-template column on the table, and the dump takes it as it stands — if the templates
  a user holds turn out to live on a table of their own instead, this becomes a join rather than a
  renamed column, and the column count of the statement stays the same either way.
- The CDR session documents carry `nasIpAddress` with a `keyword` sub-field, the same shape the
  usage join already assumes of `userName`. An aggregation on a field the mapping does not have
  returns no terms rather than an error, so a wrong guess here shows up as an empty column and not
  as a failed run — `usage.nas-ip-field` is the knob that fixes it without a rebuild.
- `sessionInstances.serviceId` is the id of the `SERVICE_INSTANCE` the usage was charged to, which
  is what `BUCKET_INSTANCE.SERVICE_ID` points at and what the dump joins the bucket through. This
  is the one assumption behind `UTLIZED_QUOTA` that cannot be settled from the reporting side, and
  it is the one the per-shard log line answers on a live cluster: rows attributed to the reported
  bundle mean it holds, none means it does not. Nothing fails either way — see
  **What `UTLIZED_QUOTA` is the total of**.
- A daily index still holds the sessions that *started* that day, including the part of one that
  ran past midnight, because cdr-service pins a session to the index it was created in. It does not
  affect a lifetime total, which sums every index; it is why `NAS_IP_ADDRESS`, which does not, is
  filtered by index rather than by a timestamp range.

## Indexes the dump relies on

- `AAA_USER(USER_NAME)` — unique or not, it lets the ordered scan avoid a three million row sort in
  temp space, and lets a shard range-scan its own slice.
- `AAA_USER_MAC_ADDRESS(USER_NAME)` and `SERVICE_INSTANCE(USERNAME)` — for the shard range
  predicates pushed into the inline views.
- `BUCKET_INSTANCE(SERVICE_ID)` — for the join back to the active bundle.

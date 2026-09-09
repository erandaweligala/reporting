# Postman — the four CSV dumps

`csv-dumps.postman_collection.json` drives `USER_DATA_DUMP`, `MAC_SERVICE_TABLE`, `PLAN_TO_BUCKET`
and `BUCKET_INSTANCE` end to end. `csv-dumps.postman_environment.json` holds the variables;
`reportTypes.csv` is a Runner data file that fires all four from the one parameterised create
request.

Import all three (Postman → Import → Files), select the **CSV dumps — local** environment, and set
`baseUrl` and `userId`.

## The three calls

Generation is asynchronous and the create call does not return the report id, so a run is always:

| Step | Call | What it gives you |
| --- | --- | --- |
| 1 | `POST /api/report-download/create` | `2001 Report Download in progress` — an acknowledgement. The row is saved as `Pending`. |
| 2 | `POST /api/report-management/filter` | The row: its `id`, and its status — `Pending` → `Processing` → `Completed`, or `Failed` / `No Records`. |
| 3 | `GET /api/report-download/download?id={id}` | The CSV, as `text/csv`, named `{id}_{REPORT_TYPE}_yyyy_MM_dd_HH_mm_ss.csv`. |

Folder 2's filter request captures `reportId` and `reportStatus` into collection variables, so
folder 3 needs no editing. Rows come back newest first, so the run you just created is
`data.reportDetails[0]`.

## Running all four

Collection Runner over folder 1 creates all four runs; at most 5 reports are in flight at once, so
none of them queue. Then, per type: set `reportType`, run *Poll until Completed* (a Runner delay of
5 000 ms is sensible — it re-sends itself until the run leaves `Processing`), then *Download report
CSV*.

The parameterised create request plus `reportTypes.csv` does the create pass in one Runner run.

## Things the collection encodes

- **Leave `format` out.** All four are streaming reports, and the mapper defaults a streaming
  report to CSV. Sending `"format": "EXCEL"` is accepted by the create call and then fails the run
  — folder 4 shows it.
- **Downloading early is not an error case to avoid.** Before the file exists the service answers
  `4005 Report file not found. Current status: Processing`, so polling that endpoint is a
  legitimate way to wait. Note the HTTP status is **500**, not 404: only response code `4000` is
  mapped to a 400.
- **`createdBy` in the filter body is ignored** server-side. Filter by `reportType` (and
  optionally `reportStatus`, `startDate`, `endDate`).
- **Use Send and Download**, or curl, for the real files. A three-million-row CSV should not be
  rendered in the response pane:

  ```
  curl -OJ "$BASE/api/report-download/download?id=$ID"
  ```

Background on what each dump contains and how a run behaves under load is in
[`../user-data-dump.md`](../user-data-dump.md) and [`../table-extracts.md`](../table-extracts.md).

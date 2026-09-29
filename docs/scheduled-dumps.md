# Scheduled dumps

The four dumps — `USER_DATA_DUMP`, `MAC_SERVICE_TABLE`, `PLAN_TO_BUCKET` and `BUCKET_INSTANCE` —
are generated every night without anyone asking for them. By default this happens at 00:30.

A scheduled run is not a second way of producing a dump. At each firing the job files one report
request per report type, exactly as `POST /api/report-download/create` files one. Each request is
a `REPORT_DOWNLOAD` row with `CREATED_BY = SCHEDULER` that starts out `Pending`. From there
everything is what a manual request gets:

- the same dispatcher and concurrency cap (five reports at a time; a dump that finds no free slot
  stays `Pending` until one frees up);
- the same watchdog;
- the same output directory, `report.output.directory`, as `<id>_<REPORT_TYPE>.csv`;
- the same listing and `GET /api/report-download/download?id=<id>`.

The firing itself only inserts four rows and hands them to the dispatcher. The dumps run on the
report workers, not on the scheduler thread.

## Configuration

Under `report.scheduled-dump` in `application.yml`:

| Property | Environment variable | Shipped value | What it does |
| --- | --- | --- | --- |
| `enabled` | `SCHEDULED_DUMP_ENABLED` | `true` | Registers the job at all. `false` registers nothing. It is `false` in code, so a deployment without this section never starts a dump by itself. |
| `cron` | `SCHEDULED_DUMP_CRON` | `0 30 0 * * *` | When the dumps are requested. It is a Spring cron expression with six fields: second, minute, hour, day of month, month, day of week. |
| `zone` | `SCHEDULED_DUMP_ZONE` | *(empty)* | Zone the cron is read in. When empty it follows `report.user-dump.timezone`, which is the zone `USER_DATA_DUMP` resolves D-1 in. A run at 00:30 then reports on the day that has just ended. |
| `report-types` | — | the four dumps | What is requested at each firing, in this order. |
| `created-by` | — | `SCHEDULER` | `CREATED_BY` on every request the job files. |

Everything is read at startup. Switching the job on or off, or moving it, is a configuration change
and a restart, not a code change. The environment variables let a deployment make it without
editing `application.yml`.

A configuration that cannot be what was meant fails the start with the property named. That covers
a cron that does not parse, a zone that does not exist, and a report type the service does not
produce. Without this check, the mistake would only show up as a night without dumps. A five-field
Unix cron (`30 0 * * *`) is one of these: Spring expects the seconds field first.

The startup log says what was decided:

```
Scheduled dumps are on: [USER_DATA_DUMP, MAC_SERVICE_TABLE, PLAN_TO_BUCKET, BUCKET_INSTANCE] at '0 30 0 * * *' in Asia/Colombo, first run at 2026-09-30T00:30+05:30[Asia/Colombo]
```

or

```
Scheduled dumps are off: report.scheduled-dump.enabled is false
```

### Examples

| Goal | Setting |
| --- | --- |
| Turn the job off | `SCHEDULED_DUMP_ENABLED=false` |
| Every day at 02:15 | `SCHEDULED_DUMP_CRON=0 15 2 * * *` |
| Weekdays only, at 00:30 | `SCHEDULED_DUMP_CRON=0 30 0 * * MON-FRI` |
| Read the cron in a zone other than the dump's | `SCHEDULED_DUMP_ZONE=Asia/Colombo` |
| Only the table extracts | `report-types: [MAC_SERVICE_TABLE, PLAN_TO_BUCKET, BUCKET_INSTANCE]` |

## Things to know

- **One instance should run it.** Every instance with the job enabled fires it, and each one files
  its own four requests. With more than one replica, enable it on one of them only. If every
  replica has to carry the same configuration, the job needs a shared lock first.
- **The four dumps take four of the five report slots.** A report requested by hand while they are
  running waits as `Pending` if the fifth slot is taken.
- **`USER_DATA_DUMP` reports D-1 in `report.user-dump.timezone`**, whenever the job fires. If
  `zone` names a different zone, 00:30 there can fall before midnight in the dump's zone, and the
  dump then reports the day before the one expected. Leaving `zone` empty avoids that.

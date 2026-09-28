"""Zurich Blue Zone parking-disc rules.

The ingested data's blue-zone `max_duration_minutes` is a flat 60 for
essentially every spot (see the analysis behind this file), but the real
rule isn't "60 minutes from arrival" -- it's time-of-day and day-of-week
dependent, per the city's own page:
https://www.stadt-zuerich.ch/de/mobilitaet/parkieren/parkbewilligungen/parkscheibe.html

- Restricted hours: Monday-Saturday, 08:00-11:30 and 13:30-18:00 only.
  "Das Einstellrad auf der Parkscheibe muss immer auf den der Ankunftszeit
  folgenden Strich eingestellt werden" -- the disc dial is set to the
  half-hour mark *following* arrival (even if arrival lands exactly on a
  mark, e.g. arriving at 9:00 sets the disc to 9:30), then you get 60
  minutes from there -- so the real usable time is 60-90 minutes
  depending on arrival minute, not a flat 60.
- Free lunch hour: arriving 11:30-13:30 isn't restricted at all, and
  carries a grace period until 14:30 regardless of exact arrival time
  within that window ("Bei einer Ankunftszeit zwischen 11.30 und 13.30
  Uhr gilt die Parkerlaubnis bis 14.30 Uhr").
- Overnight: arriving 18:00-08:00 is unrestricted, with a grace deadline
  of 09:00 the following restricted-window morning.
- Sundays: unrestricted unless a supplementary sign says otherwise (not
  modeled -- no per-street signage data).

Known simplifications, not modeled:
- Swiss/Zurich public holidays, which get the same unrestricted treatment
  as Sundays -- there's no holiday calendar wired in.
- Multi-day continuous parking (e.g. parked since Saturday night, still
  there when Monday's restricted hours resume) -- this answers "what's
  the deadline for a single arrival", not "is this car currently legal
  given how long it's been sitting there".
"""

from datetime import datetime, time, timedelta

MORNING_START = time(8, 0)
MORNING_END = time(11, 30)
LUNCH_END = time(13, 30)
EVENING_END = time(18, 0)

MAX_STAY_MINUTES = 60
DISC_MARK_MINUTES = 30

SUNDAY = 6


def _at(arrival: datetime, hour: int, minute: int) -> datetime:
    """`arrival`'s own date (and tzinfo, if it has one), at a given
    wall-clock time. `datetime.combine(date, time)` would silently drop
    tzinfo -- this is used instead so a timezone-aware `arrival` (which is
    what this rule needs -- see blue_zone_deadline's docstring) stays aware.
    """
    return arrival.replace(hour=hour, minute=minute, second=0, microsecond=0)


def _next_disc_mark(arrival: datetime) -> datetime:
    """The half-hour mark strictly after `arrival`, per the "folgenden
    Strich" (following mark) rule -- an arrival exactly on a mark still
    advances to the next one. Only called for arrivals within a restricted
    window (well before midnight), so this never needs to roll over a day.
    """
    seconds_since_midnight = (
        arrival.hour * 3600 + arrival.minute * 60 + arrival.second + arrival.microsecond / 1e6
    )
    increment = DISC_MARK_MINUTES * 60
    next_mark_seconds = (int(seconds_since_midnight // increment) + 1) * increment
    return _at(arrival, next_mark_seconds // 3600, (next_mark_seconds // 60) % 60)


def blue_zone_deadline(arrival: datetime) -> datetime | None:
    """When a car arriving at `arrival` must be moved, per the official
    Blue Zone rule -- or None if unrestricted (arrival on a Sunday; see
    the module docstring for what else this doesn't account for).

    `arrival` is read as wall-clock time in Zurich -- the rule is defined
    in terms of Zurich local hours, so callers must pass a
    datetime.now(ZoneInfo("Europe/Zurich")) (or equivalent), not a naive
    datetime.now(), which is whatever timezone the *server* happens to run
    in (UTC on Cloud Run) and would silently misjudge which rule applies.
    """
    if arrival.weekday() == SUNDAY:
        return None

    t = arrival.time()

    if t < MORNING_START or t >= EVENING_END:
        deadline = _at(arrival, 9, 0)
        if t >= EVENING_END:
            deadline += timedelta(days=1)
        return deadline

    if MORNING_END <= t < LUNCH_END:
        return _at(arrival, 14, 30)

    return _next_disc_mark(arrival) + timedelta(minutes=MAX_STAY_MINUTES)

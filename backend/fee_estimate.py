"""A deliberately rough, single flat hourly rate for white-zone (metered)
parking -- NOT real per-spot pricing. See README section 9.3 for why.

The ingested data (backend/parking_data.py) has no fee amount at all, just
a fee-liable yes/no flag (already used to set ZoneType.WHITE). Actual
Zurich meter rates depend on which tariff zone a spot is in
(Hochtarifzone/inner city vs. Niedertarifzone/everywhere else, roughly
3-4x apart) and on time of day (cheaper 22:00-06:00), and our data has no
tariff-zone field to look that up per spot even if we wanted to. Public
reporting on current rates was also inconsistent -- research turned up a
2026 fee increase that appeared to still be contested (a referendum was
reportedly threatened), so "current official rate" wasn't a stable target
to encode even at the zone level.

Given all that, this is one flat number in the middle of the general
(pre-increase) daytime range reported for meters (CHF 1.50 for the first
hour, ~3.50 for two, ~7.50 for four) -- explicitly a ballpark for "is this
roughly cheap or expensive", not a quote. The API marks it accordingly
(estimated_fee_chf_per_hour, not fee_chf_per_hour) and the UI always
labels it "rough estimate".
"""

ESTIMATED_WHITE_ZONE_RATE_CHF_PER_HOUR = 2.0

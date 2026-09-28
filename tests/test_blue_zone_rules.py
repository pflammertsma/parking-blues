from datetime import datetime

from backend.blue_zone_rules import blue_zone_deadline

# A Monday, Tuesday, Saturday, Sunday in the same week for weekday tests.
MON_DATE = (2026, 9, 28)
TUE_DATE = (2026, 9, 29)
SAT_DATE = (2026, 10, 3)
SUN_DATE = (2026, 10, 4)


def dt(date_tuple, hour, minute, second=0):
    y, m, d = date_tuple
    return datetime(y, m, d, hour, minute, second)


def test_sunday_is_unrestricted():
    assert blue_zone_deadline(dt(SUN_DATE, 10, 0)) is None
    assert blue_zone_deadline(dt(SUN_DATE, 23, 59)) is None


def test_saturday_is_restricted_like_a_weekday():
    # 09:00 arrival -> next mark 09:30 -> +60min = 10:30
    assert blue_zone_deadline(dt(SAT_DATE, 9, 0)) == dt(SAT_DATE, 10, 30)


def test_arrival_exactly_on_a_disc_mark_still_advances_to_the_next_one():
    # "folgenden Strich" -- the *following* mark, even from an exact mark.
    assert blue_zone_deadline(dt(MON_DATE, 9, 0)) == dt(MON_DATE, 10, 30)
    assert blue_zone_deadline(dt(MON_DATE, 9, 30)) == dt(MON_DATE, 11, 0)


def test_arrival_mid_interval_rounds_up_to_next_mark():
    assert blue_zone_deadline(dt(MON_DATE, 9, 1)) == dt(MON_DATE, 10, 30)
    assert blue_zone_deadline(dt(MON_DATE, 9, 29)) == dt(MON_DATE, 10, 30)
    assert blue_zone_deadline(dt(MON_DATE, 9, 31)) == dt(MON_DATE, 11, 0)


def test_evening_restricted_window_rounds_the_same_way():
    assert blue_zone_deadline(dt(TUE_DATE, 17, 35)) == dt(TUE_DATE, 19, 0)


def test_lunch_window_grants_a_flat_grace_deadline_regardless_of_arrival_minute():
    assert blue_zone_deadline(dt(MON_DATE, 11, 30)) == dt(MON_DATE, 14, 30)
    assert blue_zone_deadline(dt(MON_DATE, 12, 45)) == dt(MON_DATE, 14, 30)
    assert blue_zone_deadline(dt(MON_DATE, 13, 29, 59)) == dt(MON_DATE, 14, 30)


def test_13_30_is_the_start_of_the_evening_restricted_window_not_lunch():
    # Lunch is [11:30, 13:30), so 13:30 itself falls into the restricted
    # window: next disc mark after 13:30 is 14:00, plus 60 minutes.
    assert blue_zone_deadline(dt(MON_DATE, 13, 30)) == dt(MON_DATE, 15, 0)


def test_overnight_before_opening_gives_same_day_grace_deadline():
    assert blue_zone_deadline(dt(MON_DATE, 3, 0)) == dt(MON_DATE, 9, 0)
    assert blue_zone_deadline(dt(MON_DATE, 7, 59, 59)) == dt(MON_DATE, 9, 0)


def test_overnight_after_closing_gives_next_day_grace_deadline():
    assert blue_zone_deadline(dt(MON_DATE, 18, 0)) == dt(TUE_DATE, 9, 0)
    assert blue_zone_deadline(dt(MON_DATE, 23, 0)) == dt(TUE_DATE, 9, 0)

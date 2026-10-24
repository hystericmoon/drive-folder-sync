package io.github.hystericmoon.drivesync.sync.fakes

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the tests move by hand, for conflict dates, the 24 h full scan and the 30-day trash purge. */
class MutableClock(var now: Instant = Instant.parse("2026-10-23T09:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now += d }
}

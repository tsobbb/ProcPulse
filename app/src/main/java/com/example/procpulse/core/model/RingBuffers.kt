package com.example.procpulse.core.model

import androidx.compose.runtime.Immutable

/**
 * Fixed-size primitive circular buffer for real-time telemetry charts.
 * Avoids boxing and object allocations during DrawScope rendering.
 */
class FloatRingBuffer(val capacity: Int = 60) {
    private val data = FloatArray(capacity)
    private val timestamps = LongArray(capacity)
    private var head = 0
    var count: Int = 0
        private set

    fun push(value: Float, timestampMs: Long = System.currentTimeMillis()) {
        data[head] = value
        timestamps[head] = timestampMs
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        head = 0
        count = 0
    }

    fun valueAt(logicalIndex: Int): Float {
        if (logicalIndex < 0 || logicalIndex >= count) return 0f
        val start = if (count < capacity) 0 else head
        val physical = (start + logicalIndex) % capacity
        return data[physical]
    }

    fun timestampAt(logicalIndex: Int): Long {
        if (logicalIndex < 0 || logicalIndex >= count) return 0L
        val start = if (count < capacity) 0 else head
        val physical = (start + logicalIndex) % capacity
        return timestamps[physical]
    }

    fun latest(): Float = if (count == 0) 0f else valueAt(count - 1)

    fun minOrZero(): Float {
        if (count == 0) return 0f
        var minVal = Float.MAX_VALUE
        for (i in 0 until count) {
            val v = valueAt(i)
            if (v < minVal) minVal = v
        }
        return minVal
    }

    fun maxOrZero(): Float {
        if (count == 0) return 0f
        var maxVal = -Float.MAX_VALUE
        for (i in 0 until count) {
            val v = valueAt(i)
            if (v > maxVal) maxVal = v
        }
        return maxVal
    }

    fun snapshotValues(): FloatArray {
        val copy = FloatArray(count)
        for (i in 0 until count) {
            copy[i] = valueAt(i)
        }
        return copy
    }

    fun snapshotTimestamps(): LongArray {
        val copy = LongArray(count)
        for (i in 0 until count) {
            copy[i] = timestampAt(i)
        }
        return copy
    }
}

@Immutable
data class ChartSeriesSnapshot(
    val values: FloatArray,
    val timestamps: LongArray,
    val min: Float,
    val max: Float,
    val current: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChartSeriesSnapshot) return false
        return values.contentEquals(other.values) &&
            timestamps.contentEquals(other.timestamps) &&
            min == other.min &&
            max == other.max &&
            current == other.current
    }

    override fun hashCode(): Int {
        var result = values.contentHashCode()
        result = 31 * result + timestamps.contentHashCode()
        result = 31 * result + min.hashCode()
        result = 31 * result + max.hashCode()
        result = 31 * result + current.hashCode()
        return result
    }

    companion object {
        val Empty = ChartSeriesSnapshot(FloatArray(0), LongArray(0), 0f, 0f, 0f)
    }
}

fun FloatRingBuffer.toSnapshot(): ChartSeriesSnapshot {
    return ChartSeriesSnapshot(
        values = snapshotValues(),
        timestamps = snapshotTimestamps(),
        min = minOrZero(),
        max = maxOrZero(),
        current = latest()
    )
}

package com.busseatalert.model

import kotlinx.serialization.Serializable

enum class SeatStatus { AVAILABLE, BOOKED, BLOCKED }

/**
 * One seat on a bus's seat map. [row]/[col] are grid units from the site; [width]/[height]
 * are how many units the seat spans (sleeper berths are two rows tall).
 */
data class Seat(
    val label: String,
    val deck: Int,
    val row: Int,
    val col: Int,
    val width: Int = 1,
    val height: Int = 1,
    val status: SeatStatus,
    /** Reserved for women (bookable only by a woman when free). */
    val ladies: Boolean = false,
    /** Already booked by a woman (Kerala/Karnataka show this). */
    val bookedByWoman: Boolean = false,
    /** Reserved for differently-abled passengers. */
    val accessible: Boolean = false,
    val fare: Int? = null,
)

data class SeatLayout(val seats: List<Seat>) {
    val decks: List<Int> get() = seats.map { it.deck }.distinct().sorted()

    /** Seats a general passenger could pick: free and not reserved for differently-abled passengers. */
    val bookable: Int get() = seats.count { it.status == SeatStatus.AVAILABLE && !it.accessible }

    /** Where the free seats are, for comparing buses (window vs aisle, lower vs upper deck). */
    fun summary(): SeatSummary {
        val open = seats.filter { it.status == SeatStatus.AVAILABLE && !it.accessible && !it.ladies }
        val twoDecks = decks.size > 1
        // Outermost columns of each deck are by the window.
        val edges = seats.groupBy { it.deck }.mapValues { (_, s) -> s.minOf { it.col } to s.maxOf { it.col + it.width - 1 } }
        fun isWindow(seat: Seat): Boolean = when {
            WINDOW_LABEL.matches(seat.label) -> true  // TNSTC labels window seats "12W"
            AISLE_LABEL.matches(seat.label) -> false  // ... and aisle seats "11A"
            else -> edges.getValue(seat.deck).let { (lo, hi) -> seat.col == lo || seat.col + seat.width - 1 == hi }
        }
        val window = open.count(::isWindow)
        val lowerDeck = decks.firstOrNull()
        return SeatSummary(
            free = open.size,
            window = window,
            aisle = open.size - window,
            lowerDeck = if (twoDecks) open.count { it.deck == lowerDeck } else 0,
            upperDeck = if (twoDecks) open.count { it.deck != lowerDeck } else 0,
            ladiesOnly = seats.count { it.status == SeatStatus.AVAILABLE && it.ladies },
            sleeper = seats.any { it.height > 1 || BERTH_LABEL.containsMatchIn(it.label) },
        )
    }

    private companion object {
        val WINDOW_LABEL = Regex("""^\d+\s*W$""", RegexOption.IGNORE_CASE)
        val AISLE_LABEL = Regex("""^\d+\s*A$""", RegexOption.IGNORE_CASE)
        val BERTH_LABEL = Regex("""\d\s*[UL]B$""", RegexOption.IGNORE_CASE)
    }
}

/**
 * Free seats on a bus by position, from its seat map. Counts leave out seats reserved for women
 * ([ladiesOnly] is separate) and for differently-abled passengers. Decks are 0 on single-deck buses.
 */
@Serializable
data class SeatSummary(
    val free: Int,
    val window: Int,
    val aisle: Int,
    val lowerDeck: Int,
    val upperDeck: Int,
    val ladiesOnly: Int,
    val sleeper: Boolean,
)

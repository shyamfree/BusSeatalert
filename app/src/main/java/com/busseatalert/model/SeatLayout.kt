package com.busseatalert.model

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
}

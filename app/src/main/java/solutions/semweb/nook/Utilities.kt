package solutions.semweb.nook

/**
 * A generic data class for holding four values
 *
 * @param A type of the first value
 * @param B type of the second value
 * @param C type of the third value
 * @param D type of the fourth value
 * @param first First value
 * @param second Second value
 * @param third Third value
 * @param fourth Fourth value
 */
data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
) {
    /**
     * Returns string representation of the Quadruple
     */
    override fun toString(): String = "($first, $second, $third, $fourth)"

    /**
     * Converts Quadruple to a list
     */
    fun toList(): List<Any?> = listOf(first, second, third, fourth)

    /**
     * Converts Quadruple to a Pair of Pairs
     */
    fun toPairOfPairs(): Pair<Pair<A, B>, Pair<C, D>> =
        Pair(Pair(first, second), Pair(third, fourth))

    /**
     * Converts Quadruple to a Triple with Pair
     */
    fun toTripleWithPair(): Triple<A, B, Pair<C, D>> =
        Triple(first, second, Pair(third, fourth))
}
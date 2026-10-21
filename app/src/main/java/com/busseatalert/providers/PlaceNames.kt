package com.busseatalert.providers

/** Old/new and English/local spellings that the three sites use inconsistently. */
object PlaceNames {
    private val groups = listOf(
        setOf("palakkad", "palghat"),
        setOf("bengaluru", "bangalore"),
        setOf("mysuru", "mysore"),
        setOf("mangaluru", "mangalore"),
        setOf("thiruvananthapuram", "trivandrum"),
        setOf("kochi", "cochin", "ernakulam"),
        setOf("kozhikode", "calicut"),
        setOf("thrissur", "trichur"),
        setOf("alappuzha", "alleppey"),
        setOf("kannur", "cannanore"),
        setOf("kollam", "quilon"),
        setOf("chennai", "madras"),
        setOf("tiruchirappalli", "trichy"),
        setOf("thoothukudi", "tuticorin"),
        setOf("kanyakumari", "cape comorin"),
        setOf("udhagamandalam", "ooty"),
        setOf("belagavi", "belgaum"),
        setOf("kalaburagi", "gulbarga"),
        setOf("hubballi", "hubli"),
        setOf("shivamogga", "shimoga"),
    )

    /** Strips site decorations such as "Palakkad (9)" -> "palakkad". */
    fun normalize(name: String): String = name.replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .lowercase().replace(Regex("""\s+"""), " ").trim()

    /** The query itself first, then its other known spellings. */
    fun variants(query: String): List<String> {
        val q = normalize(query)
        val group = groups.firstOrNull { q in it }.orEmpty()
        return listOf(q) + (group - q)
    }

    /** Best match among [names]: exact, then starts-with, then contains, trying every spelling. */
    fun <T> best(query: String, items: List<T>, name: (T) -> String): T? {
        val spellings = variants(query)
        val normalized = items.map { it to normalize(name(it)) }
        for (test in listOf<(String, String) -> Boolean>(
            { n, q -> n == q }, { n, q -> n.startsWith(q) }, { n, q -> n.contains(q) },
        )) {
            for (q in spellings) normalized.firstOrNull { test(it.second, q) }?.let { return it.first }
        }
        return null
    }
}

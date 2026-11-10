package com.example.drivecanvas.core

/**
 * Road class table (SPEC 3.1). [speedKmh] is the class free-flow speed used for travel time;
 * [planFactor] scales the routing cost.
 */
data class RoadClass(
    val code: Int,
    val osmHighway: String,
    val speedKmh: Double,
    val planFactor: Double,
) {
    companion object {
        val ALL: List<RoadClass> = listOf(
            RoadClass(0, "trunk", 60.0, 1.2),
            RoadClass(1, "trunk_link", 40.0, 1.0),
            RoadClass(2, "primary", 50.0, 1.0),
            RoadClass(3, "primary_link", 35.0, 1.0),
            RoadClass(4, "secondary", 45.0, 1.0),
            RoadClass(5, "secondary_link", 30.0, 1.0),
            RoadClass(6, "tertiary", 40.0, 1.0),
            RoadClass(7, "tertiary_link", 30.0, 1.0),
            RoadClass(8, "unclassified", 30.0, 1.2),
            RoadClass(9, "residential", 25.0, 1.3),
        )

        private val byOsm: Map<String, RoadClass> = ALL.associateBy { it.osmHighway }
        private val byCode: Map<Int, RoadClass> = ALL.associateBy { it.code }

        /** Road class for an OSM `highway` value, or null when the way is not kept. */
        fun fromOsm(highway: String): RoadClass? = byOsm[highway]

        fun fromCode(code: Int): RoadClass =
            byCode[code] ?: throw IllegalArgumentException("Unknown road class code $code")
    }
}

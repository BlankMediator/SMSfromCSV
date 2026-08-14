package com.blankmediator.smsfromcsv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimRouterTest {
    private val activeSims = listOf(
        SimTarget(
            subscriptionId = 11,
            slotNumber = 1,
            displayName = "Personal",
            carrierName = "Carrier A",
            phoneNumber = "+61411111111"
        ),
        SimTarget(
            subscriptionId = 22,
            slotNumber = 2,
            displayName = "Work",
            carrierName = "Carrier B",
            phoneNumber = "+61422222222"
        )
    )

    @Test
    fun routesWholeBatchThroughSelectedSim() {
        val table = CsvParser.parse("phone\n+61400000001\n+61400000002")
        val compiled = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        val routed = SimRouter.route(table, compiled, SimRoutingMode.FIXED, 22, activeSims)

        assertTrue(routed.errors.isEmpty())
        assertEquals(listOf(22, 22), routed.messages.map { it.subscriptionId })
        assertTrue(routed.messages.all { it.simLabel.contains("SIM 2") })
    }

    @Test
    fun routesCsvRowsBySlotAliasAndExposedPhoneNumber() {
        val table = CsvParser.parse(
            "phone,sim\n" +
                "+61400000001,SIM1\n" +
                "+61400000002,+61422222222"
        )
        val compiled = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        val routed = SimRouter.route(table, compiled, SimRoutingMode.PER_ROW, null, activeSims)

        assertTrue(routed.errors.isEmpty())
        assertEquals(listOf(11, 22), routed.messages.map { it.subscriptionId })
    }

    @Test
    fun routesCsvRowByExplicitSubscriptionId() {
        val table = CsvParser.parse("phone,sim\n+61400000001,sub:22")
        val compiled = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        val routed = SimRouter.route(table, compiled, SimRoutingMode.PER_ROW, null, activeSims)

        assertTrue(routed.errors.isEmpty())
        assertEquals(22, routed.messages.single().subscriptionId)
    }

    @Test
    fun blocksPerRowRoutingWithoutSimColumn() {
        val table = CsvParser.parse("phone\n+61400000001")
        val compiled = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        val routed = SimRouter.route(table, compiled, SimRoutingMode.PER_ROW, null, activeSims)

        assertTrue(routed.messages.isEmpty())
        assertTrue(routed.errors.single().contains("column named 'sim'"))
    }

    @Test
    fun blocksUnknownAndAmbiguousSimSelectors() {
        val duplicateCarrierSims = activeSims.map { it.copy(carrierName = "Same Carrier") }
        val table = CsvParser.parse(
            "phone,sim\n" +
                "+61400000001,missing\n" +
                "+61400000002,Same Carrier"
        )
        val compiled = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        val routed = SimRouter.route(table, compiled, SimRoutingMode.PER_ROW, null, duplicateCarrierSims)

        assertTrue(routed.messages.isEmpty())
        assertTrue(routed.errors.any { it.contains("cannot match") })
        assertTrue(routed.errors.any { it.contains("ambiguous") })
    }
}

package com.orange.echocards

import com.orange.echocards.domain.speech.OperationGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧回调失效规则，对应 docs/reference/speech-api.md 第 5、6 节与节点 1 的 V03。
 */
class OperationGateTest {

    @Test
    fun acceptsOnlyActiveOperation() {
        val gate = OperationGate()
        gate.begin("op-1")

        assertTrue(gate.accept("op-1"))
        assertFalse(gate.accept("op-2"))
        assertEquals("op-1", gate.activeOperationId)
    }

    @Test
    fun invalidateDropsLateCallbacks() {
        val gate = OperationGate()
        gate.begin("op-1")
        assertEquals("op-1", gate.invalidate())

        assertFalse(gate.accept("op-1"))
        assertFalse(gate.acceptTerminal("op-1"))
        assertEquals(2, gate.ignored)
        assertNull(gate.activeOperationId)
    }

    @Test
    fun newOperationInvalidatesPreviousOne() {
        val gate = OperationGate()
        gate.begin("op-1")
        gate.begin("op-2")

        assertFalse(gate.accept("op-1"))
        assertFalse(gate.acceptTerminal("op-1"))
        assertTrue(gate.accept("op-2"))
    }

    @Test
    fun terminalEventIsDeliveredAtMostOnce() {
        val gate = OperationGate()
        gate.begin("op-1")

        assertTrue(gate.acceptTerminal("op-1"))
        assertFalse(gate.acceptTerminal("op-1"))
        assertEquals(1, gate.ignored)
    }

    @Test
    fun terminatedOperationStopsAcceptingNormalEvents() {
        val gate = OperationGate()
        gate.begin("op-1")
        assertTrue(gate.acceptTerminal("op-1"))

        // 取消后到达的 partial_result 不能再影响界面
        assertFalse(gate.accept("op-1"))
        assertEquals(1, gate.ignored)
    }
}

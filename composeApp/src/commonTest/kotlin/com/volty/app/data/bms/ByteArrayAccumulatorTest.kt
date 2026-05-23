package com.volty.app.data.bms

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ByteArrayAccumulatorTest {

    @Test
    fun `append concatenates chunks`() {
        val acc = ByteArrayAccumulator()
        acc.append(byteArrayOf(1, 2, 3))
        acc.append(byteArrayOf(4, 5))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), acc.toByteArray())
        assertEquals(5, acc.size)
    }

    @Test
    fun `trimLeading removes N bytes from the front`() {
        val acc = ByteArrayAccumulator()
        acc.append(byteArrayOf(1, 2, 3, 4, 5))
        acc.trimLeading(2)
        assertContentEquals(byteArrayOf(3, 4, 5), acc.toByteArray())
    }

    @Test
    fun `trimLeading more than size empties buffer`() {
        val acc = ByteArrayAccumulator()
        acc.append(byteArrayOf(1, 2))
        acc.trimLeading(5)
        assertEquals(0, acc.size)
    }

    @Test
    fun `reset empties buffer`() {
        val acc = ByteArrayAccumulator()
        acc.append(byteArrayOf(9, 9, 9))
        acc.reset()
        assertEquals(0, acc.size)
    }
}

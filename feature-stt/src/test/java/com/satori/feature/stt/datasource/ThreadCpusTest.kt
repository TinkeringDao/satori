package com.satori.feature.stt.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The CPUs a thread may run on, as its status in /proc lists them. */
class ThreadCpusTest {

    /** The part of a thread's status around the CPUs, as the razr's kernel writes it. */
    private fun status(list: String?) = buildString {
        appendLine("Name:\tDefaultDispatch")
        appendLine("State:\tR (running)")
        appendLine("Tgid:\t21263")
        appendLine("Pid:\t21481")
        appendLine("Cpus_allowed:\t7f")
        if (list != null) appendLine("Cpus_allowed_list:\t$list")
        appendLine("Mems_allowed:\t1")
        appendLine("voluntary_ctxt_switches:\t1520")
    }

    @Test
    fun `the list is read, not the mask before it`() {
        assertEquals("0-6", ThreadCpus.allowedList(status("0-6")))
        assertEquals("0-2", ThreadCpus.allowedList(status("0-2")))
        assertEquals("0-3,7", ThreadCpus.allowedList(status("0-3,7")))
    }

    @Test
    fun `a status without the list, or with an empty one, says nothing`() {
        assertNull(ThreadCpus.allowedList(status(null)))
        assertNull(ThreadCpus.allowedList(status("")))
        assertNull(ThreadCpus.allowedList(""))
    }

    @Test
    fun `a thread that isn't there says nothing`() {
        assertNull(ThreadCpus.of(-1))
    }
}

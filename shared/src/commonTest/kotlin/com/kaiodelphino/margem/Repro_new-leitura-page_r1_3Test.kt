@file:OptIn(ExperimentalAtomicApi::class)

package com.kaiodelphino.margem

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Repro: a Leitura widget read cancelled while it waits for one of the readers (its produceState restarted
 * on a version bump) must not run its SQL once a reader frees up: the stale query only delays the fresh ones.
 *
 * The SQLite driver (the I/O boundary) is real, wrapped only to hold the readers' BEGIN until released and to
 * count the leituraLines queries that reach a connection.
 */
class `Repro_new-leitura-page_r1_3Test` {
    private val path = Path(SystemTemporaryDirectory, "margem-repro-r1-3-${Random.nextLong().toULong()}.db").toString()
    private var db: Db? = null

    private val gate = CompletableDeferred<Unit>()
    private val held = AtomicInt(0)
    private val leituraQueries = AtomicInt(0)

    private inner class Conn(val c: SQLiteConnection) : SQLiteConnection {
        var reader = false
        override fun prepare(sql: String): SQLiteStatement {
            if (sql.contains("query_only")) reader = true
            if (reader && sql == "BEGIN" && !gate.isCompleted) {
                held.incrementAndFetch()
                runBlocking { gate.await() }
            }
            if (sql.contains("FROM installments i JOIN transactions t")) leituraQueries.incrementAndFetch()
            return c.prepare(sql)
        }
        override fun inTransaction(): Boolean = c.inTransaction()
        override fun close() = c.close()
    }

    private val driver = object : SQLiteDriver {
        val real = BundledSQLiteDriver()
        override fun open(fileName: String): SQLiteConnection = Conn(real.open(fileName))
    }

    @AfterTest
    fun cleanup() {
        if (!gate.isCompleted) gate.complete(Unit)
        runCatching { db?.close() }
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            runCatching { SystemFileSystem.delete(Path(path + suffix), mustExist = false) }
        }
    }

    @Test
    fun `a Leitura read cancelled while waiting for a reader never runs its query`() = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) {
                val db = Db(driver, path).also { db = it }

                // Other screens' reads hold all four readers (D29).
                val busy = List(4) { launch { db.settings() } }
                while (held.load() < 4) delay(10)

                // A Leitura widget's read parks waiting for a reader, then its produceState restarts (version bump).
                val stale = launch { db.leituraLines(LeituraQuery(LocalDate(2000, 1, 1), LocalDate(2100, 12, 31))) }
                delay(300)
                stale.cancel()

                gate.complete(Unit)
                (busy + stale).joinAll()

                assertEquals(0, leituraQueries.load(), "the cancelled Leitura read still ran its SQL on a reader")
            }
        }
    }
}

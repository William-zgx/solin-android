package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `search()` is called by the agent loop outside the presentation-layer memory mutex while
 * rebuild / model switching / forget run on another thread. The repository must therefore be
 * internally thread-safe: no ConcurrentModificationException from the entry map, and no use of a
 * semantic runtime after it has been closed by a concurrent model switch.
 */
class MemoryRepositoryConcurrencyTest {
    @Test
    fun concurrentSearchWhileRebuildingAndSwitchingModelsIsSafe() {
        val createdRuntimes = Collections.synchronizedList(mutableListOf<CloseTrackingEmbeddingRuntime>())
        val useAfterClose = AtomicInteger(0)
        val repository = MemoryRepository(
            semanticRuntimeFactory = { path ->
                CloseTrackingEmbeddingRuntime(path, useAfterClose).also(createdRuntimes::add)
            },
            recordStore = SynchronizedMemoryRecordStore(),
        )
        repeat(40) { index ->
            repository.indexPreference("pref-$index", "回答保持简洁 咖啡 $index")
        }
        val messages = (1L..60L).map { index ->
            ChatMessage(
                id = index,
                role = MessageRole.User,
                text = "我喜欢咖啡和旅行 第$index 次",
                privacy = MessagePrivacy.RemoteEligible,
            )
        }

        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val running = AtomicBoolean(true)
        val start = CountDownLatch(1)
        val readers = (0 until 3).map {
            Thread {
                start.await()
                while (running.get()) {
                    try {
                        repository.search("咖啡 旅行 简洁", topK = 3)
                        repository.semanticMemoryRuntimeStatus
                        repository.semanticMemoryIndexedRecordCount
                    } catch (throwable: Throwable) {
                        errors += throwable
                    }
                }
            }.apply { start() }
        }
        val writer = Thread {
            start.await()
            try {
                repeat(150) { iteration ->
                    repository.useMemoryModel("/models/embedding-$iteration")
                    repository.rebuild(messages)
                    repository.indexPreference("pref-extra-$iteration", "回答保持简洁 咖啡 extra $iteration")
                    repository.forget("pref-extra-$iteration")
                    if (iteration % 3 == 0) repository.useMemoryModel(null)
                }
            } catch (throwable: Throwable) {
                errors += throwable
            } finally {
                running.set(false)
            }
        }.apply { start() }

        start.countDown()
        writer.join(TimeUnit.SECONDS.toMillis(60))
        running.set(false)
        readers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

        assertTrue("concurrent memory access threw: ${errors.take(3)}", errors.isEmpty())
        assertEquals("semantic runtime used after close", 0, useAfterClose.get())
        assertTrue(createdRuntimes.isNotEmpty())
    }

    private class CloseTrackingEmbeddingRuntime(
        path: String,
        private val useAfterClose: AtomicInteger,
    ) : EmbeddingRuntime {
        @Volatile
        private var closed = false
        override val modelId: String = "close-tracking-$path"
        override val dimension: Int = 2
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.5f

        override fun embed(text: String): FloatArray {
            if (closed) useAfterClose.incrementAndGet()
            // Widen the window in which a concurrent close() could race this call.
            Thread.yield()
            if (closed) useAfterClose.incrementAndGet()
            return if ("咖啡" in text || "简洁" in text) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }

        override fun close() {
            closed = true
        }
    }

    private class SynchronizedMemoryRecordStore : MemoryRecordStore {
        private val records = linkedMapOf<String, PersistedMemoryRecord>()

        @Synchronized
        override fun records(): List<PersistedMemoryRecord> = records.values.toList()

        @Synchronized
        override fun upsert(record: PersistedMemoryRecord) {
            records[record.id] = record
        }

        @Synchronized
        override fun delete(id: String): Boolean = records.remove(id) != null

        @Synchronized
        override fun clear() {
            records.clear()
        }
    }
}

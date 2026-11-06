package com.applemusicktv.media

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** Compare-and-remove must dispatch to ConcurrentMap (API 1), never Map (API 24).
 * Job cleanup supplies a Job supertype while the stored value is a Deferred. */
internal class OwnedTaskRegistry<K, V : Any> {
    private val tasks: ConcurrentMap<K, Any> = ConcurrentHashMap()
    @Suppress("UNCHECKED_CAST")
    operator fun get(key: K): V? = tasks[key] as V?
    operator fun set(key: K, value: V) { tasks[key] = value }
    fun remove(key: K, owner: Any?): Boolean = owner != null && tasks.remove(key, owner)
    @Suppress("UNCHECKED_CAST")
    fun forEach(block: (Map.Entry<K, V>) -> Unit) {
        for (entry in tasks.entries) block(entry as Map.Entry<K, V>)
    }
}

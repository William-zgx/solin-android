package com.bytedance.zgx.pocketmind.storage

data class ZvecNativeStatus(
    val available: Boolean,
    val detail: String,
)

object ZvecNativeStore {
    private const val LIBRARY_NAME = "zvec_bridge"

    fun probe(): ZvecNativeStatus =
        runCatching {
            System.loadLibrary(LIBRARY_NAME)
            ZvecNativeStatus(available = true, detail = "loaded $LIBRARY_NAME")
        }.getOrElse { error ->
            ZvecNativeStatus(
                available = false,
                detail = error.message ?: error::class.java.name,
            )
        }
}

package com.noreal.locbridge

import android.content.Context
import android.net.Uri

/** 模块配置：哪些包需要被接管，从哪个端口订阅坐标流。 */
internal data class BridgeConfig(val targets: Set<String>, val port: Int) {

    companion object {
        val DEFAULT = BridgeConfig(setOf("com.tencent.mm"), 38765)

        /**
         * 从 provider 读配置。读不到就用默认值，保证核心功能不依赖 UI 进程是否活着。
         */
        fun read(context: Context?): BridgeConfig {
            val resolver = context?.contentResolver ?: return DEFAULT
            return try {
                resolver.query(
                    Uri.parse("content://${BridgeProvider.AUTHORITY}"),
                    null,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return DEFAULT
                    val targets = cursor.getString(0)
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.toSet()
                        .orEmpty()
                    val port = cursor.getInt(1)
                    if (targets.isEmpty()) DEFAULT else BridgeConfig(targets, port)
                } ?: DEFAULT
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}

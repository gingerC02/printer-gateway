package com.delivery.gateway

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 网关本地存储：把小程序投送过来的内容（含 ESC/POS 字节）以 JSONL 形式落盘。
 * 只存不自动删——清理完全由用户在 StorageActivity 手动操作。
 * 文件位置：/sdcard/Android/data/com.delivery.gateway/files/records/records.jsonl
 */
object RecordStore {

    private lateinit var file: File

    fun init(ctx: Context) {
        val dir = File(ctx.getExternalFilesDir(null), "records")
        if (!dir.exists()) dir.mkdirs()
        file = File(dir, "records.jsonl")
        if (!file.exists()) file.createNewFile()
    }

    data class Record(
        val id: String,        // 本地唯一 id
        val code: String,      // 打印编号（小程序 order.code，如 20261001-0003）
        val ts: Long,          // 详细时间：网关收到投送的时刻
        val printedAt: Long,   // 打印时间：网关真正写入 USB 的时刻（0 表示尚未打印）
        val text: String,      // 打印内容：可读文本（多行）
        val bytes: String      // ESC/POS 指令（base64），用于手动重打
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("code", code)
            put("ts", ts)
            put("printedAt", printedAt)
            put("text", text)
            put("bytes", bytes)
        }

        companion object {
            fun fromJson(o: JSONObject): Record = Record(
                o.optString("id", ""),
                o.optString("code", ""),
                o.optLong("ts", 0),
                o.optLong("printedAt", 0),
                o.optString("text", ""),
                o.optString("bytes", "")
            )
        }
    }

    @Synchronized
    fun append(r: Record) {
        file.appendText(r.toJson().toString() + "\n")
    }

    @Synchronized
    fun loadAll(): List<Record> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<Record>()
        BufferedReader(FileReader(file)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    try { out.add(Record.fromJson(JSONObject(line))) } catch (_: Exception) { /* 跳过损坏行 */ }
                }
                line = reader.readLine()
            }
        }
        out.sortByDescending { it.ts }   // 按收到时间倒序，最新在前
        return out
    }

    /** 标记某条记录为已打印（更新打印时间） */
    @Synchronized
    fun markPrinted(id: String, ts: Long) {
        rewrite { list -> list.map { if (it.id == id) it.copy(printedAt = ts) else it } }
    }

    @Synchronized
    fun deleteById(id: String) {
        rewrite { it.filterNot { r -> r.id == id } }
    }

    /** 删除某一天（[dayStart, dayEnd) 毫秒区间）的记录 */
    @Synchronized
    fun deleteByDay(dayStart: Long, dayEnd: Long) {
        rewrite { it.filterNot { r -> r.ts in dayStart until dayEnd } }
    }

    /** 删除时间段 [start, end] 内的记录（含端点） */
    @Synchronized
    fun deleteByRange(start: Long, end: Long) {
        rewrite { it.filterNot { r -> r.ts in start..end } }
    }

    @Synchronized
    fun deleteAll() {
        file.writeText("")
    }

    fun count(): Int = loadAll().size

    /** 读取→变换→整体写回，保证删除原子性 */
    private fun rewrite(transform: (List<Record>) -> List<Record>) {
        val kept = transform(loadAll())
        val sb = StringBuilder()
        for (r in kept) sb.append(r.toJson().toString()).append("\n")
        file.writeText(sb.toString())
    }
}

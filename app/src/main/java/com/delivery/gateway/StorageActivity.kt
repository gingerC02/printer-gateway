package com.delivery.gateway

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

/**
 * 网关本地打印记录的查询、清理与手动打印界面。
 * 查询：列表（新→旧）/ 编号或内容关键字搜索。
 * 清理：单条删 / 按天删 / 按时间段删 / 一键全清。全部手动，无自动删除。
 * 手动打印：在“自动打印”关闭时，逐条点击“打印”将已存档记录发送到打印机。
 */
class StorageActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnDelDay: Button
    private lateinit var btnDelRange: Button
    private lateinit var btnClear: Button

    private var all: List<RecordStore.Record> = emptyList()
    private var filtered: List<RecordStore.Record> = emptyList()
    private lateinit var adapter: RecordAdapter

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage)
        RecordStore.init(applicationContext)

        listView = findViewById(R.id.listView)
        etSearch = findViewById(R.id.etSearch)
        tvCount = findViewById(R.id.tvCount)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnDelDay = findViewById(R.id.btnDelDay)
        btnDelRange = findViewById(R.id.btnDelRange)
        btnClear = findViewById(R.id.btnClear)

        adapter = RecordAdapter()
        listView.adapter = adapter
        listView.emptyView = tvEmpty

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val rec = filtered.getOrNull(position) ?: return@setOnItemClickListener
            val printLabel = if (rec.printedAt > 0) fmt.format(Date(rec.printedAt)) else "未打印"
            AlertDialog.Builder(this)
                .setTitle(if (rec.code.isNotEmpty()) "单号：${rec.code}" else "（无单号）")
                .setMessage("详细时间：${fmt.format(Date(rec.ts))}\n打印时间：$printLabel\n\n${rec.text}")
                .setPositiveButton(R.string.close, null)
                .show()
        }

        btnDelDay.setOnClickListener { pickDayForDelete() }
        btnDelRange.setOnClickListener { pickRangeForDelete() }
        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_title)
                .setMessage(R.string.confirm_clear)
                .setPositiveButton(R.string.confirm) { _, _ -> RecordStore.deleteAll(); refresh() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        refresh()
    }

    private fun refresh() {
        all = RecordStore.loadAll()
        applyFilter()
        tvCount.text = getString(R.string.count_format, all.size)
    }

    private fun applyFilter() {
        val q = etSearch.text.toString().trim().lowercase(Locale.CHINA)
        filtered = if (q.isEmpty()) all else all.filter {
            it.code.lowercase(Locale.CHINA).contains(q) || it.text.lowercase(Locale.CHINA).contains(q)
        }
        adapter.setData(filtered)
        adapter.notifyDataSetChanged()
    }

    private fun deleteRecord(rec: RecordStore.Record) {
        val label = if (rec.code.isNotEmpty()) rec.code else fmt.format(Date(rec.ts))
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_title)
            .setMessage("删除该条记录（${label}）？")
            .setPositiveButton(R.string.confirm) { _, _ -> RecordStore.deleteById(rec.id); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // 手动打印指定记录
    private fun manualPrint(rec: RecordStore.Record) {
        if (rec.bytes.isEmpty()) { toast(R.string.no_print_data); return }
        val ok = GatewayService.requestPrint(rec.bytes)
        if (ok) {
            RecordStore.markPrinted(rec.id, System.currentTimeMillis())
            toast(R.string.printed_ok)
            refresh()
        } else {
            toast(R.string.print_failed)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    // 选某一天删除
    private fun pickDayForDelete() {
        pickDay { y, m, d ->
            val (start, end) = dayRange(y, m, d)
            val n = all.count { it.ts in start until end }
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_title)
                .setMessage("将删除 ${fmt.format(Date(start))} 当天的 ${n} 条记录，不可恢复？")
                .setPositiveButton(R.string.confirm) { _, _ -> RecordStore.deleteByDay(start, end); refresh() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // 选起止日期删除时间段
    private fun pickRangeForDelete() {
        pickDay { sy, sm, sd ->
            val start = dayRange(sy, sm, sd).first
            pickDay { ey, em, ed ->
                val end = dayRange(ey, em, ed).second
                val lo = minOf(start, end)
                val hi = maxOf(start, end)
                val n = all.count { it.ts in lo..hi }
                AlertDialog.Builder(this)
                    .setTitle(R.string.confirm_title)
                    .setMessage("将删除 ${n} 条记录（${fmt.format(Date(lo))} ~ ${fmt.format(Date(hi))}），不可恢复？")
                    .setPositiveButton(R.string.confirm) { _, _ -> RecordStore.deleteByRange(lo, hi); refresh() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun pickDay(onPicked: (y: Int, m: Int, d: Int) -> Unit) {
        val c = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d -> onPicked(y, m, d) },
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH),
            c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun dayRange(y: Int, m: Int, d: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(y, m, d, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis
        return start to end
    }

    private inner class RecordAdapter : BaseAdapter() {
        private var items: List<RecordStore.Record> = emptyList()

        fun setData(d: List<RecordStore.Record>) { items = d }

        override fun getCount(): Int = items.size
        override fun getItem(i: Int): Any = items[i]
        override fun getItemId(i: Int): Long = i.toLong()

        override fun getView(i: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@StorageActivity)
                .inflate(R.layout.row_record, parent, false)
            val rec = items[i]
            val tvHead = v.findViewById<TextView>(R.id.tvHead)
            val tvBody = v.findViewById<TextView>(R.id.tvBody)
            val btnPrint = v.findViewById<Button>(R.id.btnPrint)
            val btnDel = v.findViewById<Button>(R.id.btnDel)

            val lines = rec.text.lineSequence().toList()
            val snippet = (lines.firstOrNull() ?: "").take(48)
            val more = if (lines.size > 1) " …(+${lines.size - 1}行)" else ""
            val printLabel = if (rec.printedAt > 0) fmt.format(Date(rec.printedAt)) else "未打印"

            tvHead.text = "单号：${if (rec.code.isNotEmpty()) rec.code else "(无)"}　详细：${fmt.format(Date(rec.ts))}　打印：$printLabel"
            tvBody.text = snippet + more

            btnPrint.setOnClickListener { manualPrint(rec) }
            btnDel.setOnClickListener { deleteRecord(rec) }
            return v
        }
    }
}

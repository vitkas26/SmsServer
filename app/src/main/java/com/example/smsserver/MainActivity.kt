package com.example.smsserver

import android.Manifest
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.pm.PackageManager
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.smsserver.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: SmsRepository
    private lateinit var gateway: GatewayServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    private var selectedKind = "inbox"
    private var startDay: LocalDate = LocalDate.now()
    private var endDay: LocalDate = LocalDate.now()
    private var loadVersion = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasSmsPermissions()) startGateway()
        else binding.serverStatus.setText(R.string.server_missing_permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = SmsRepository(applicationContext)
        gateway = GatewayServer(
            applicationContext,
            repository,
            GatewayToken(applicationContext).value,
            placeCall = { phone ->
                withContext(Dispatchers.Main) {
                    try {
                        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            },
            onSent = { runOnUiThread { if (selectedKind == "sent") loadMessages() } },
        )
        binding.gatewayToken.setText(R.string.token_hint)
        binding.gatewayToken.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SmsServer token", GatewayToken(applicationContext).value))
            binding.gatewayToken.setText(R.string.token_copied)
        }
        binding.toggleServer.setOnClickListener {
            if (running) stopGateway()
            else permissionLauncher.launch(
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE),
            )
        }
        binding.inboxButton.setOnClickListener { selectedKind = "inbox"; loadMessages() }
        binding.sentButton.setOnClickListener { selectedKind = "sent"; loadMessages() }
        binding.startDate.setOnClickListener { pickDate(true) }
        binding.endDate.setOnClickListener { pickDate(false) }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val spacing = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(spacing + bars.left, spacing + bars.top, spacing + bars.right, spacing + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        renderStatus()
        loadMessages()
    }

    private fun startGateway() {
        binding.toggleServer.isEnabled = false
        scope.launch {
            try {
                gateway.start()
                running = true
            } catch (error: Exception) {
                binding.serverStatus.text = getString(R.string.server_error, error.message ?: "unknown error")
            } finally {
                binding.toggleServer.isEnabled = true
                if (running) renderStatus()
                loadMessages()
            }
        }
    }

    private fun stopGateway() {
        binding.toggleServer.isEnabled = false
        scope.launch {
            try {
                gateway.stop()
                running = false
                renderStatus()
            } catch (error: Exception) {
                binding.serverStatus.text = getString(R.string.server_error, error.message ?: "unknown error")
            } finally {
                binding.toggleServer.isEnabled = true
            }
        }
    }

    private fun hasSmsPermissions(): Boolean = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun renderStatus() {
        binding.serverStatus.setText(if (running) R.string.server_running else R.string.server_stopped)
        binding.toggleServer.setText(if (running) R.string.stop else R.string.start)
        binding.serverAddress.visibility = if (running) View.VISIBLE else View.GONE
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        binding.startDate.text = startDay.format(formatter)
        binding.endDate.text = endDay.format(formatter)
        binding.inboxButton.isEnabled = selectedKind != "inbox"
        binding.sentButton.isEnabled = selectedKind != "sent"
    }

    private fun pickDate(start: Boolean) {
        val current = if (start) startDay else endDay
        DatePickerDialog(this, { _, year, month, day ->
            val picked = LocalDate.of(year, month + 1, day)
            if (start) {
                startDay = picked
                if (endDay < picked) endDay = picked
            } else {
                endDay = picked
                if (startDay > picked) startDay = picked
            }
            renderStatus()
            loadMessages()
        }, current.year, current.monthValue - 1, current.dayOfMonth).show()
    }

    private fun loadMessages() {
        val version = ++loadVersion
        val kind = selectedKind
        val zone = ZoneId.systemDefault()
        val from = Date.from(startDay.atStartOfDay(zone).toInstant())
        val to = Date.from(endDay.plusDays(1).atStartOfDay(zone).toInstant())
        scope.launch {
            val messages = try {
                withContext(Dispatchers.IO) {
                    if (kind == "inbox") repository.inbox(from, to) else repository.sent(from, to)
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (version != loadVersion) return@launch
            val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val lines = messages.map { "${formatter.format(Date(it.timestamp))}  ${it.phone}\n${it.body}" }
            binding.messageList.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, lines)
            binding.emptyMessage.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
            renderStatus()
        }
    }

    override fun onDestroy() {
        runBlocking { gateway.stop() }
        scope.cancel()
        super.onDestroy()
    }
}

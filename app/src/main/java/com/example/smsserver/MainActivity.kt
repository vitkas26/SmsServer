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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.smsserver.databinding.ActivityMainBinding
import com.example.smsserver.databinding.DialogOperatorCodesBinding
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
    private lateinit var simRouting: SimRouting
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
        simRouting = SimRouting(applicationContext)
        val (sim1Phone, sim2Phone) = simRouting.numbers()
        binding.sim1Phone.setText(sim1Phone)
        binding.sim2Phone.setText(sim2Phone)
        binding.saveRoutes.setOnClickListener { saveRoutes() }
        binding.operatorCodes.setOnClickListener { showOperatorCodes() }
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
            onUnrouted = { phone ->
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.operator_code_missing, phone), Toast.LENGTH_LONG).show()
                }
            },
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
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE),
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
        Manifest.permission.READ_PHONE_STATE,
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun saveRoutes() {
        val sim1Phone = binding.sim1Phone.text.toString().trim()
        val sim2Phone = binding.sim2Phone.text.toString().trim()
        if (!GatewayPolicy.validPhone(sim1Phone)) {
            binding.sim1Phone.error = getString(R.string.invalid_phone)
            return
        }
        if (!GatewayPolicy.validPhone(sim2Phone) || sim1Phone == sim2Phone) {
            binding.sim2Phone.error = getString(R.string.invalid_or_duplicate_phone)
            return
        }
        simRouting.save(sim1Phone, sim2Phone)
        binding.routeStatus.setText(R.string.routes_saved)
    }

    private fun showOperatorCodes() {
        val fields = DialogOperatorCodesBinding.inflate(layoutInflater)
        val (sim1Prefixes, sim2Prefixes) = simRouting.prefixes()
        fields.sim1Codes.setText(sim1Prefixes.joinToString(", "))
        fields.sim2Codes.setText(sim2Prefixes.joinToString(", "))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.operator_codes)
            .setView(fields.root)
            .setPositiveButton(R.string.save_routes, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = try {
                    SimRouting.parsePrefixes(fields.sim1Codes.text.toString())
                } catch (_: IllegalArgumentException) {
                    fields.sim1Codes.error = getString(R.string.invalid_operator_codes)
                    return@setOnClickListener
                }
                val second = try {
                    SimRouting.parsePrefixes(fields.sim2Codes.text.toString())
                } catch (_: IllegalArgumentException) {
                    fields.sim2Codes.error = getString(R.string.invalid_operator_codes)
                    return@setOnClickListener
                }
                if (first.intersect(second.toSet()).isNotEmpty()) {
                    fields.sim2Codes.error = getString(R.string.duplicate_operator_code)
                    return@setOnClickListener
                }
                simRouting.savePrefixes(first, second)
                binding.routeStatus.setText(R.string.operator_codes_saved)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

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

package com.example.smsserver

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import android.widget.SimpleAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.room.*
import com.example.smsserver.databinding.ActivityMainBinding
import com.example.smsserver.databinding.FragmentFirstBinding
import com.example.smsserver.databinding.FragmentSecondBinding
import com.google.android.material.tabs.TabLayout
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.InternalAPI
import io.ktor.util.toLocalDateTime
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.collections.HashMap
import kotlin.system.exitProcess


@Entity(tableName = "dataStock")
data class LogData(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    @ColumnInfo(name = "tel")
    val tel: String,
    @ColumnInfo(name = "sms")
    val sms: String,
    @ColumnInfo(name = "date")
    val nowData: Date
)

@Dao
interface DaoData {
    @Insert
    fun insertData(vararg insertData: LogData)

    @Query("SELECT * FROM dataStock")
    fun getAll(): List<LogData>

    @Query("DELETE FROM dataStock")
    fun nukeTable()

    @Query("SELECT * FROM dataStock WHERE date BETWEEN :dayst AND :dayet")
    fun getFromTable(dayst: Date?, dayet: Date?): List<LogData>

    @Query("SELECT * FROM dataStock ORDER BY Date DESC LIMIT 1")
    fun getLastFromTable(): List<LogData>
}

@InternalAPI
class MainActivity : AppCompatActivity() {
    private var dateParse: Date? = null
    private var dateParse1: Date? = null
    private val TEXT_NAME = "text"
    private val TEXT_ID = "position"
    private val adapterData = ArrayList<Map<String, Any>>()
    private val adapterData1 = ArrayList<Map<String, Any>>()
    private lateinit var binding: ActivityMainBinding
    private lateinit var bindingFirstFragment: FragmentFirstBinding
    private lateinit var bindingSecondFragment: FragmentSecondBinding
    private var thread: Job? = null
    var counter = false

    @SuppressLint("SimpleDateFormat")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        bindingFirstFragment = FragmentFirstBinding.inflate(layoutInflater)
        bindingSecondFragment = FragmentSecondBinding.inflate(layoutInflater)

        val view = binding.root
        setContentView(view)

        val wifiMan = baseContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInf = wifiMan.connectionInfo
        val ipAddress = wifiInf.ipAddress
        val ip = String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )

        val date = DateTimeFormatter.ISO_DATE.format(LocalDate.now())
        val parser = SimpleDateFormat("yyyy-MM-dd")
        val formatter = SimpleDateFormat("dd.MM.yyyy")
        val formattedDate = formatter.format(parser.parse(date))

        binding.button.setOnClickListener { end() }
        binding.textView5.setText(formattedDate)
        binding.textView3.setText(formattedDate)
        binding.textView2.text = ip

        binding.textView3.setOnClickListener {
            setDate()
        }
        binding.textView5.setOnClickListener {
            setDate1()
        }

        val fragmentAdapter = MyPagerAdapter(supportFragmentManager)

        binding.viewPager.adapter = fragmentAdapter

        binding.tabLayout.setupWithViewPager(binding.viewPager)
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                getInfobyDate()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                readSms()
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        val selected = binding.tabLayout.getTabAt(0)
        selected?.select()
        read()
    }

    data class Request(
        val tel: String,
        val message: String
    )

    data class CallRequest(
        val tel: String
    )

    private fun startServer() {
        thread = GlobalScope.launch {
            embeddedServer(Netty, 9900) {
                install(ContentNegotiation) {
                    gson {}
                }
                routing {
                    get("/") {
                        val message = readSms()
                        call.respond(mapOf("message" to message))
                    }
                }
                routing {
                    post("/") {
                        val request = call.receive<Request>()
                        call.respond(request)
                        val tel = request.tel
                        val message = request.message
                        send(tel, message)
                    }
                }
                routing {
                    post("/call") {
                        val request = call.receive<CallRequest>()
                        call.respond(request)
                        val tel = request.tel
                        call(tel)
                    }
                }
            }.start(wait = false)
        }
        thread!!.start()
        Log.d("@@@", "startServer: server started")
    }


    @SuppressLint("MissingPermission")
    private fun send(tel: String, message: String) {

        val sent = "SMS_SENT"
        val delivered = "SMS_DELIVERED"

        val piSent = PendingIntent.getBroadcast(
            applicationContext,
            0, Intent(sent), 0
        )

        val piDelivered = PendingIntent.getBroadcast(
            applicationContext,
            0, Intent(delivered), 0
        )


//        конвертирование слов в цифры
//        val arrText = message.split(" ".toRegex())
//        val numMap = mapOf("ноль" to 0,"один" to 1,"два" to 2,"три" to 3,"четыре" to 4,"пять" to 5,"шесть" to 6,"семь" to 7,"восемь" to 8,"девять" to 9)
//        var result = String()
//        for (item in arrText) {
//            if (item in numMap){
//                result+=numMap[item]
//            }
//        }
//        println(result)

        registerReceiver(object : BroadcastReceiver() {
            @Override
            override fun onReceive(arg0: Context, arg1: Intent) {
                when (resultCode) {
                    Activity.RESULT_OK ->
                        Toast.makeText(
                            baseContext, "SMS sent",
                            Toast.LENGTH_SHORT
                        ).show()
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                        Toast.makeText(
                            baseContext, "Generic failure",
                            Toast.LENGTH_SHORT
                        ).show()
                    SmsManager.RESULT_ERROR_NO_SERVICE ->
                        Toast.makeText(
                            baseContext, "No service",
                            Toast.LENGTH_SHORT
                        ).show()
                    SmsManager.RESULT_ERROR_NULL_PDU ->
                        Toast.makeText(
                            baseContext, "Null PDU",
                            Toast.LENGTH_SHORT
                        ).show()
                    SmsManager.RESULT_ERROR_RADIO_OFF ->
                        Toast.makeText(
                            baseContext, "Radio off",
                            Toast.LENGTH_SHORT
                        ).show()
                }
            }
        }, IntentFilter(sent))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(arg0: Context, arg1: Intent) {
                when (resultCode) {
                    Activity.RESULT_OK -> Toast.makeText(
                        baseContext, "SMS delivered",
                        Toast.LENGTH_SHORT
                    ).show()
                    Activity.RESULT_CANCELED -> Toast.makeText(
                        baseContext, "SMS not delivered",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, IntentFilter(delivered))

        if (tel == "0550890380")
            SmsManager.getSmsManagerForSubscriptionId(1)
                .sendTextMessage(tel, null, message, piSent, piDelivered)

        if (tel == "0880173333")
            SmsManager.getSmsManagerForSubscriptionId(0)
                .sendTextMessage(tel, null, message, piSent, piDelivered)
//else
//        SmsManager.getDefault().sendTextMessage(tel, null, message, piSent, piDelivered)
        println("sent")
        val date = Date.from(Instant.now())
        val data = LogData(0, tel, message, date)

        AppDatabase.getInstance(applicationContext)?.daoData()?.insertData(data)
        if (tel == "null" || message == "null") {
            println("no data")
        } else {
            getInfobyDate()
        }
    }

    @SuppressLint("MissingPermission")
    private fun call(tel: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$tel"))
        startActivity(intent)
    }

    private fun end() {
        if (counter) {
            binding.button.text = "СТАРТ"
            binding.button.setTextColor(Color.parseColor("#FFFFFF"))
            binding.button.background = getDrawable(R.drawable.button_start)
            binding.textView6.text = "Приложение не работает"
            binding.textView6.setTextColor(Color.parseColor("#D42A2A"))
            Toast.makeText(
                baseContext, "Приложение остановлено",
                Toast.LENGTH_SHORT
            ).show()

            if (thread != null) {
                thread!!.cancel()
            }

            counter = false
        } else {
            binding.button.text = "СТОП"
            binding.button.setTextColor(Color.parseColor("#D42A2A"))
            binding.button.background = getDrawable(R.drawable.button_rounded)
            binding.textView6.text = "Приложение работает"
            binding.textView6.setTextColor(Color.parseColor("#50BF34"))
            Toast.makeText(
                baseContext, "Приложение запущено",
                Toast.LENGTH_SHORT
            ).show()
            getInfobyDate()
            counter = true
            startServer()
        }
    }

    fun read() {
        if (ContextCompat.checkSelfPermission(
                baseContext,
                "android.permission.READ_SMS"
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            getInfobyDate()
            readSms()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.READ_SMS"), 500)
        }

    }

    @SuppressLint("SimpleDateFormat", "SetTextI18n")
    fun setDate() {

        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR)
        val month = c.get(Calendar.MONTH)
        val day = c.get(Calendar.DAY_OF_MONTH)
        val dpd = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                // Display Selected date in TextView
                val decimalDate = String.format("%02d", dayOfMonth)
                val decimalMonth = String.format("%02d", month + 1)
                val simpleDate = ("$decimalDate.$decimalMonth.$year")
                binding.textView3.setText(simpleDate)
                dateParse = SimpleDateFormat("dd.MM.yyyy").parse(simpleDate)
                getInfobyDate()
                readSms()
            }, year, month, day
        )
        dpd.show()
    }

    @SuppressLint("SimpleDateFormat", "SetTextI18n")
    fun setDate1() {

        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR)
        val month = c.get(Calendar.MONTH)
        val day = c.get(Calendar.DAY_OF_MONTH)
        val dpd = DatePickerDialog(
            this, DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
                // Display Selected date in TextView
                val decimalDate = String.format("%02d", dayOfMonth)
                val decimalDate1 = String.format("%02d", dayOfMonth + 1)
                val decimalMonth = String.format("%02d", month + 1)
                val date1 = ("$decimalDate.$decimalMonth.$year")
                val date2 = ("$decimalDate1.$decimalMonth.$year")
                binding.textView5.setText(date1)
                dateParse1 = SimpleDateFormat("dd.MM.yyyy").parse(date2)
                getInfobyDate()
                readSms()
            }, year, month, day
        )
        dpd.show()
    }

    fun getInfobyDate() {

        var date1 = dateParse1
        var date = dateParse

        if (date == null) {
            date = Date.from(Instant.now().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofHours(6)))
        }
        if (date1 == null) {
            date1 = Date.from(Instant.now().plus(Duration.ofDays(1)))
        }
        if (date != null && date1 != null) {
            val textLog =
                AppDatabase.getInstance(applicationContext)?.daoData()?.getFromTable(date, date1)
            if (textLog.isNullOrEmpty()) {
                adapterData.clear()
            } else {
                adapterData.clear()
                var index = 1

                for (logData in textLog) {
                    val m: Map<String, Any>
                    m = HashMap()
                    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm")
                    val formattedDate =
                        formatter.format(parser.parse(logData.nowData.toLocalDateTime().toString()))
                    val string = ("$formattedDate       ${logData.tel}\nПИН-код: ${logData.sms}")
                    m.put(TEXT_ID, "$index. ")
                    m.put(TEXT_NAME, string)
                    adapterData.add(m)
                    index += 1
                }
                val from = arrayOf(TEXT_ID, TEXT_NAME)
                val to = intArrayOf(R.id.label, R.id.label1)
                val sAdapter = SimpleAdapter(this, adapterData, R.layout.listview_item, from, to)
                runOnUiThread {
                    bindingFirstFragment.listview.adapter = sAdapter
                }
            }
        }
    }

    @SuppressLint("Recycle")
    fun readSms(): String {
        var date1 = dateParse
        var date2 = dateParse1

        if (date1 == null) {
            date1 = Date.from(Instant.now().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofHours(6)))
        }
        if (date2 == null) {
            date2 = Date.from(Instant.now().plus(Duration.ofDays(1)))
        }

        adapterData1.removeAll { true }

        val names = arrayOf("_id", "address", "date", "body")
        val cursor = contentResolver.query(
            Uri.parse("content://sms/inbox"),
            names,
            null,
            null,
            null,
            null
        )
        if (ContextCompat.checkSelfPermission(
                baseContext,
                "android.permission.READ_SMS"
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (cursor != null) {
                var i = 1
                cursor.moveToLast()
                while (cursor.moveToPrevious()) {
                    val m: Map<String, Any>
                    m = HashMap()
                    var msgData = ""
                    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm")
                    val date = cursor.getString(2).toLong()
                    val parseDate = Date(date).toLocalDateTime().toString()
                    val checkDate = Date(date)
                    val formattedDate = formatter.format(parser.parse(parseDate))

                    msgData += formattedDate + " " + cursor.getString(1) + "\nПИН-код: " + cursor.getString(
                        3
                    )

                    if (date1 != null && date2 != null && date1 < checkDate && date2 >= checkDate) {
                        m.put(TEXT_ID, "$i. ")
                        m.put(TEXT_NAME, msgData)
                        adapterData1.add(m)
                        i++
                    } else
                        println("do not match")
                }

                val from = arrayOf(TEXT_ID, TEXT_NAME)
                val to = intArrayOf(R.id.label, R.id.label1)
                val sAdapter = SimpleAdapter(this, adapterData1, R.layout.listview_item, from, to)
                runOnUiThread {
                    bindingSecondFragment.secondList.adapter = sAdapter
                }
            }
        }
        return if (adapterData1.isEmpty())
            "no data found"
        else
            adapterData1[0].getValue("text").toString()
    }
}
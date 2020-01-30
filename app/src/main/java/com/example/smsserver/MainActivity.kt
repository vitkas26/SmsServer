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
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.room.*
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
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main.view.*
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.system.exitProcess


@Entity(tableName = "dataStock")
data class LogData (
    //    constructor(val id: Long, val tel: String, val sms: String,val nowData: Date) : this(0L, tel, sms,nowData)
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
    fun insertData (vararg insertData: LogData)

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
    private var dateParse:Date?=null
    private var dateParse1:Date?=null
    var array = mutableListOf<String>()
    var array1 = mutableListOf<String>()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        val parser =  SimpleDateFormat("yyyy-MM-dd")
        val formatter = SimpleDateFormat("dd.MM.yyyy")
        val formattedDate = formatter.format(parser.parse(date))
        textView5.setText(formattedDate)
        textView3.setText(formattedDate)
        textView2.text = ip
        textView.setOnClickListener{
            read()
        }
        textView9.setOnClickListener{
            readSms()
        }
        read()
        startServer()
        textView3.setOnClickListener{
            setDate()
        }
        textView5.setOnClickListener{
            setDate1()
        }
    }

    data class Request (
        val tel : String,
        val message: String
    )
    data class CallRequest (
        val tel : String
    )


    private fun startServer() {
            embeddedServer(Netty, 8080) {
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            send(tel, message)
                        }
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

    @RequiresApi(Build.VERSION_CODES.O)
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

registerReceiver(object: BroadcastReceiver(){
        @Override
        override fun onReceive(arg0:Context, arg1:Intent ) {
            when (resultCode)
            {
                 Activity.RESULT_OK->
                    Toast.makeText(baseContext, "SMS sent",
                            Toast.LENGTH_SHORT).show()
                 SmsManager.RESULT_ERROR_GENERIC_FAILURE->
                    Toast.makeText(baseContext, "Generic failure",
                            Toast.LENGTH_SHORT).show();
                 SmsManager.RESULT_ERROR_NO_SERVICE->
                    Toast.makeText(
                        baseContext, "No service",
                            Toast.LENGTH_SHORT).show();
                 SmsManager.RESULT_ERROR_NULL_PDU->
                    Toast.makeText(
                        baseContext, "Null PDU",
                            Toast.LENGTH_SHORT).show();
                 SmsManager.RESULT_ERROR_RADIO_OFF->
                    Toast.makeText(
                        baseContext, "Radio off",
                            Toast.LENGTH_SHORT).show()
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

        SmsManager.getDefault().sendTextMessage(tel, null, message, piSent, piDelivered)
        println("sent")
        val date = Date.from(Instant.now())
        val data = LogData(0,tel,message,date)
        val adapter = ArrayAdapter<String>(this, R.layout.listview_item, array)
        AppDatabase.getInstance(applicationContext)?.daoData()?.insertData(data)
            if(tel=="null"||message=="null"){
            println("no data")
        }
        else {
                adapter.clear()
                val textLog  = AppDatabase.getInstance(applicationContext)?.daoData()?.getAll()
                if (textLog != null) {
                    var index = 1
                    for (logData in textLog){
                        val parser =  SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                        val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm")
                        val formattedDate = formatter.format(parser.parse(logData.nowData.toLocalDateTime().toString()))
                        val string = ("$index. $formattedDate       ${logData.tel}\nПИН-код: ${logData.sms}")
                    array.add(string)
                        index += 1
                    }
                    runOnUiThread {
                        main.listView.adapter = adapter
                    }
                }
            }
    }

@SuppressLint("MissingPermission")
private fun call(tel: String){
    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$tel"))
    startActivity(intent)
}

     fun end(view: View) {
        exitProcess(-1)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun read() {
        getInfobyDate()
    }

    @SuppressLint("SimpleDateFormat", "SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.O)
    fun setDate() {

        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR)
        val month = c.get(Calendar.MONTH)
        val day = c.get(Calendar.DAY_OF_MONTH)
        val dpd = DatePickerDialog(
            this,
            DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
                // Display Selected date in TextView
                val decimalDate = String.format("%02d", dayOfMonth)
                val decimalMonth = String.format("%02d", month + 1)
                val simpleDate = ("$decimalDate.$decimalMonth.$year")
                main.textView3.setText(simpleDate)
                dateParse = SimpleDateFormat("dd.MM.yyyy").parse(simpleDate)
                getInfobyDate()
            }, year, month, day
        )
        dpd.show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("SimpleDateFormat", "SetTextI18n")
    fun setDate1() {

        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR)
        val month = c.get(Calendar.MONTH)
        val day = c.get(Calendar.DAY_OF_MONTH)
        val dpd = DatePickerDialog(
            this,
            DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
                // Display Selected date in TextView
                val decimalDate = String.format("%02d", dayOfMonth)
                val decimalMonth = String.format("%02d", month+1)
                val date1 =("$decimalDate.$decimalMonth.$year")
                main.textView5.setText(date1)
                dateParse1 = SimpleDateFormat("dd.MM.yyyy").parse(date1)
                getInfobyDate()
            },year,month,day)
        dpd.show()
    }
    @RequiresApi(Build.VERSION_CODES.O)
    fun getInfobyDate() {
        line.visibility = View.VISIBLE
        line2.visibility = View.INVISIBLE
        var date1 = dateParse1
        var date = dateParse
        array.removeAll {true}
        val adapter = ArrayAdapter<String>(this,
            R.layout.listview_item, array)
        if (date==null){
           date = Date.from(Instant.now())
        }
        if (date1==null){
            date1 = Date.from(Instant.now())
        }
        if (date != null && date1 != null) {
            val textLog =
                AppDatabase.getInstance(applicationContext)?.daoData()?.getFromTable(date, date1)
            if (textLog.isNullOrEmpty()){
                adapter.clear()
                runOnUiThread {
                    main.listView.adapter = adapter
                }
            }
            else {
                adapter.clear()
                var index = 1
                for (logData in textLog) {
                    val parser =  SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm")
                    val formattedDate = formatter.format(parser.parse(logData.nowData.toLocalDateTime().toString()))
                    val string = ("$index. $formattedDate       ${logData.tel}\nПИН-код: ${logData.sms}")
                    array.add(string)
                    index +=1
                }
                runOnUiThread {
                    main.listView.adapter = adapter
                }
            }
        }
    }
    @SuppressLint("Recycle")
    private fun readSms():String{
        line.visibility = View.INVISIBLE
        line2.visibility = View.VISIBLE
        array1.removeAll {true}
        val adapter = ArrayAdapter<String>(this,
            R.layout.listview_item, array1)

        val names= arrayOf("_id", "address", "date", "body")
        val cursor = contentResolver.query(Uri.parse("content://sms/inbox"),names, null, null, null, null)
        if(ContextCompat.checkSelfPermission(baseContext, "android.permission.READ_SMS") == PackageManager.PERMISSION_GRANTED) {
            if (cursor != null) {
                var i = 1
                while (cursor.moveToNext()) { // must check the result to prevent exception
                    var msgData = ""
                    val parser =  SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm")
                    val date = cursor.getString(2).toLong()
                    val parseDate = Date(date).toLocalDateTime().toString()
                    val formattedDate = formatter.format(parser.parse(parseDate))

                    msgData += "$i. " + formattedDate + " " + cursor.getString(1) + "\n ПИН-код: " + cursor.getString(3)
                    array1.add(msgData)
                    i++
                }
                runOnUiThread {
                    main.listView.adapter = adapter
                }
            }
        }
        return array1.first()
    }
}
package com.stm.bledemo.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.stm.bledemo.BLEApplication
import com.stm.bledemo.activity.connection.ConnectionInterface
import com.stm.bledemo.activity.connection.ETXOTAPacketType
import com.stm.bledemo.activity.connection.ETX_OTA_ACK
import com.stm.bledemo.activity.connection.ETX_OTA_DATA_MAX_SIZE
import com.stm.bledemo.activity.connection.ETX_OTA_SOF
import com.stm.bledemo.activity.connection.EXT_OTA_DATA
import com.stm.bledemo.activity.scan.ScanAdapter
import com.stm.bledemo.activity.scan.ScanInterface
import com.stm.bledemo.extension.toHexString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit

const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val GATT_MAX_MTU_SIZE = 128
private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"
private val RESEND_DELAY_MS = 500
val YOUR_SERVICE_UUID = "D973F2E0-B19E-11E2-9E96-0800200C9A66"
val YOUR_WRITE_CHARACTERISTIC_UUID = "D973F2E2-B19E-11E2-9E96-0800200C9A66"
val YOUR_NOTIFY_CHARACTERISTIC_UUID = "D973F2E1-B19E-11E2-9E96-0800200C9A66"
@Suppress("unused")
@SuppressLint("NotifyDataSetChanged", "MissingPermission")
object BLEManager {

    var scanInterface: ScanInterface? = null
    var connectionInterface: ConnectionInterface? = null

    var bGatt: BluetoothGatt? = null
    var scanAdapter: ScanAdapter? = null

    // BLE Queue System (Coroutines)
    private val channel = Channel<BLEResult>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var isScanning = false
    private var isConnected = false
    var deviceNameFilter = ""
    var deviceRSSIFilter = ""

    // List of BLE Scan Results
    val scanResults = mutableListOf<ScanResult>()

    val bAdapter: BluetoothAdapter by lazy {
        val bluetoothManager =
            BLEApplication.app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner: BluetoothLeScanner by lazy {
        bAdapter.bluetoothLeScanner
    }
    const val writeCharacteristicUuid =  "D973F2E2-B19E-11E2-9E96-0800200C9A66"
    const val notifyCharacteristicUuid = "D973F2E1-B19E-11E2-9E96-0800200C9A66"
    const val acknowledgmentFlag = 0x01.toByte()

    val acknowledgmentMutex = Mutex()

    // CoroutineScope for managing coroutines
    var acknowledgmentReceived = true
    var Re_Transmit_Flag =false
    var Check_Notify =false
    var Last_Data_Frame: ByteArray? = null

    /** Bluetooth 5 */

    @RequiresApi(Build.VERSION_CODES.O)
    fun checkBluetooth5Support() {
        Timber.i("LE 2M PHY Supported: ${bAdapter.isLe2MPhySupported}")
        Timber.i("LE Coded PHY Supported: ${bAdapter.isLeCodedPhySupported}")
        Timber.i("LE Extended Advertising Supported: ${bAdapter.isLeExtendedAdvertisingSupported}")
        Timber.i("LE Periodic Advertising Supported: ${bAdapter.isLePeriodicAdvertisingSupported}")
    }

    /** BLE Scan */

    @SuppressLint("ObsoleteSdkInt")
    fun startScan(context: Context) {
        if (!hasPermissions(context)) {
            scanInterface?.requestPermissions()
        } else if (!isScanning) {
            scanResults.clear()
            scanAdapter?.notifyDataSetChanged()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                checkBluetooth5Support()
            }

            bleScanner.startScan(null, scanSettings, scanCallback)
            isScanning = true
            Timber.i("BLE Scan Started")
        }
    }

    fun stopScan() {
        if (isScanning) {
            bleScanner.stopScan(scanCallback)
            isScanning = false
            Timber.i("BLE Scan Stopped")
        }
    }

    // Set Scan Settings (Low Latency High Power Usage)
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    // Scan Result Callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val indexQuery = scanResults.indexOfFirst { it.device.address == result.device.address }

            if (indexQuery != -1) { // Updates Existing Scan Result
                scanResults[indexQuery] = result
                scanAdapter?.notifyItemChanged(indexQuery)
            } else { // Adds New Scan Result
                with(result.device) {
                    Timber.i("Found BLE device! Name: ${name ?: "Unnamed"}, address: $address")
                }

                // Check if Device Name & RSSI match filters
                val filterComparison =
                    scanAdapter?.filterCompare(result, deviceNameFilter, "name") == true &&
                    scanAdapter?.filterCompare(result, deviceRSSIFilter, "rssi") == true

                // Adds scanned device item to Recycler View if not filtered out
                if (filterComparison) {
                    scanResults.add(result)
                    scanAdapter?.notifyItemInserted(scanResults.size - 1)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("Scan Failed! Code: $errorCode")
        }
    }

    /** BLE Connection */

    // Connects to Scan Result Device
    fun connect(result: ScanResult, context: Context) {
        if (!isConnected) {
            stopScan()

            with(result.device) {
                connectGatt(context, false, gattCallback)
                Timber.i("Connecting to $address")
            }
        }
    }

    // Disconnects from Device
    fun disconnect() {
        if (isConnected) bGatt?.disconnect()
    }

    // Connection Callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device?.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnected = true
                    Timber.i("Successfully connected to $deviceAddress")

                    deviceNameFilter = ""
                    deviceRSSIFilter = ""

                    bGatt = gatt
                    scanInterface?.startIntent()

                    Handler(Looper.getMainLooper()).post {
                        bGatt!!.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnected = false
                    Timber.i("Successfully disconnected from $deviceAddress")
                    connectionInterface?.finishActivity()
                    gatt.close()
                }
            } else {
                isConnected = false
                val message = "Connection Attempt Failed for $deviceAddress! Error: $status"
                Timber.e(message)
                scanInterface?.startToast(message)

                connectionInterface?.finishActivity()
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt) {
                Timber.i("Discovered ${services.size} services for ${device.address}")
                printGattTable()

                connectionInterface?.addDiscoveredItems()

                scope.launch {
                    requestMTU(GATT_MAX_MTU_SIZE)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Timber.i("ATT MTU changed to $mtu, Success: ${status == BluetoothGatt.GATT_SUCCESS}")
            channel.offer(BLEResult("MTU", null, status))
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        connectionInterface?.valueUpdated(characteristic)
                        Timber.i("Read characteristic $uuid:\n${value.toHexString()}")
                    }
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        Timber.e("Read not permitted for $uuid!")
                    }
                    else -> {
                        Timber.e("Characteristic read failed for $uuid, Error: $status")
                    }
                }

                channel.offer(BLEResult(characteristic.uuid.toString(), value, status))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Timber.i("Wrote to characteristic ${this.uuid} | value: ${this.value?.toHexString()}")
                    }
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                        Timber.e("Write exceeded connectionInterface ATT MTU!")
                    }
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Timber.e("Write not permitted for ${this.uuid}!")
                    }
                    else -> {
                        Timber.e("Characteristic write failed for ${this.uuid}, error: $status")
                    }
                }

                channel.offer(BLEResult(characteristic.uuid.toString(), value, status))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic
        ) {
            with(characteristic) {
                connectionInterface?.valueUpdated(characteristic)
           //     Timber.i("Characteristic ${this.uuid} changed | value: ${this.value?.toHexString()}")
                if (characteristic.uuid == UUID.fromString(notifyCharacteristicUuid)) {
                    Check_Notify=true
                }

            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            with (descriptor) {
                channel.offer(BLEResult(uuid.toString(), value, status))
            }
        }


    } // End of Connection Callback

        suspend fun startDataTransmission(dataList: List<ByteArray>) {
            var lastData: ByteArray? = null
            val writeCharacteristic = getCharacteristics(writeCharacteristicUuid)
            val notifyCharacteristic = getCharacteristics(notifyCharacteristicUuid)
            try {
        // Enable notifications for the notify characteristic
//        bGatt?.setCharacteristicNotification(notifyCharacteristic, true)
//        // Configure the descriptor for notifications
//        val descriptor = notifyCharacteristic?.getDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID))
//        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//        bGatt?.writeDescriptor(descriptor)
        // Set the flag to wait for acknowledgment
        //acknowledgmentReceived = true

            for (data in dataList) {
            //    Last_Data_Frame=data
                lastData=data
                writeCharacteristic(writeCharacteristic,data)
                acknowledgmentMutex.withLock {
                    acknowledgmentReceived = true // Reset the flag for the next data packet
               }
                waitForAcknowledgment(writeCharacteristic,notifyCharacteristic,lastData)
            }
            } catch (e: Exception) {
                // Handle the error here, and then resend the last data packet
                if (lastData != null) {
                    delay(RESEND_DELAY_MS.toLong())
                    writeCharacteristic(writeCharacteristic,lastData)
                }
                // Handle other error scenarios as needed
            }
        }

    suspend fun sendotadata(otaArray :ByteArray)
    {
        var lastData: ByteArray? = null
        var dataIndex=0
        var endIndex=ETX_OTA_DATA_MAX_SIZE
        val numberOfChunks=(otaArray.size)/ETX_OTA_DATA_MAX_SIZE
        val remaningData=(otaArray.size)%ETX_OTA_DATA_MAX_SIZE
        val writeCharacteristic = getCharacteristics(writeCharacteristicUuid)
        val notifyCharacteristic = getCharacteristics(notifyCharacteristicUuid)
        try {
            for (i in 0 until numberOfChunks) {
                val payload = otaArray.copyOfRange(dataIndex, endIndex)
                val sendotaframe = EXT_OTA_DATA.send_ota_data(
                                    ETXOTAPacketType.ETX_OTA_PACKET_TYPE_DATA,
                                    payload.size.toUShort(), payload.toUByteArray(), i.toUInt()
                                    ).OtaDataByteArray(payload.size.toShort())
                lastData=sendotaframe
                writeCharacteristic(writeCharacteristic, sendotaframe)
                dataIndex = endIndex
                endIndex += ETX_OTA_DATA_MAX_SIZE
                acknowledgmentMutex.withLock {
                    acknowledgmentReceived = true // Reset the flag for the next data packet
                }
                waitForAcknowledgment(writeCharacteristic,notifyCharacteristic,sendotaframe)

            }
        }catch (e: Exception) {
            // Handle the error here, and then resend the last data packet
            if (lastData != null) {
                delay(RESEND_DELAY_MS.toLong())
                writeCharacteristic(writeCharacteristic,lastData)
            }
            // Handle other error scenarios as needed
        }
        if(remaningData!=0)
        {
           val rpayload= otaArray.copyOfRange(dataIndex,(otaArray.size))
            val rsendotaframe=  EXT_OTA_DATA.send_ota_data(
                ETXOTAPacketType.ETX_OTA_PACKET_TYPE_DATA,
                rpayload.size.toUShort(), rpayload.toUByteArray(), 0u
            ).OtaDataByteArray(rpayload.size.toShort())
            writeCharacteristic(writeCharacteristic,rsendotaframe)
       //     Last_Data_Frame=rsendotaframe
            lastData=rsendotaframe
            acknowledgmentMutex.withLock {
                acknowledgmentReceived = true // Reset the flag for the next data packet
            }
            waitForAcknowledgment(writeCharacteristic,notifyCharacteristic,rsendotaframe)

        }

    }
    private fun getCharacteristics(characteristicUuid: String): BluetoothGattCharacteristic? {
        val service = bGatt?.getService(UUID.fromString(YOUR_SERVICE_UUID))
        return service?.getCharacteristic(UUID.fromString(characteristicUuid))
    }

    // Function to start data transmission
    @SuppressLint("MissingPermission")
     suspend fun waitForAcknowledgment(Re_Send_Characteristic: BluetoothGattCharacteristic?,
                                       notify_Characteristics : BluetoothGattCharacteristic?,
                                       last_data:ByteArray)
     {
        acknowledgmentMutex.withLock {
            while (acknowledgmentReceived) {
          /*Check if there is any callback of onChange Characteristics*/
                if(Check_Notify){
          /*Check the Received data is ACK or NACK*/
                    val acknowledgment = Receive_ACK(notify_Characteristics)
                    if (acknowledgment == acknowledgmentFlag) {
                        acknowledgmentReceived = false // Reset the flag for the next data packet
                        Check_Notify=false
                    }
                    /*Resend the Last data when NACK Received */
                    else{
                        writeCharacteristic(Re_Send_Characteristic, last_data)
                        Check_Notify=false
                    }
                }
                delay(100) // Non-blocking delay
            }
        }
    }

     fun Receive_ACK(readCharacteristic:BluetoothGattCharacteristic?): Byte {
        val read= readCharacteristic?.value
        val packet_type :UByte= 3u
        var ret :Byte=0
         if(read!=null) {
             if (read[0].toUByte() != ETX_OTA_SOF) {
                 Timber.e("Start Of Frame Error \r\n")
                 ret = -1
             }
             if (read[1].toUByte() == packet_type) {
                 if (read[4].toUByte() == ETX_OTA_ACK) {
                     ret = acknowledgmentFlag
                 }
             }
         }
        return ret
    }

    // Prints UUIDs of Available services & characteristics from Bluetooth Gatt
    private fun BluetoothGatt.printGattTable() {
        if (services.isEmpty()) {
            Timber.i("No service and characteristic available, call discoverServices() first?")
            return
        }

        services.forEach { service ->
            val characteristicsTable = service.characteristics.joinToString(
                separator = "\n|--",
                prefix = "|--"
            ) { it.uuid.toString() }

            Timber.i("\nService: ${service.uuid}\nCharacteristics:\n$characteristicsTable")
        }
    }

    // Request to Change MTU Size
    suspend fun requestMTU(size: Int): BLEResult? {
        bGatt?.requestMtu(size)
        return waitForResult("MTU")
    }

    /** Characteristic (Read/Write) */

    // Get a Characteristic using Service & Characteristic UUIDs
    private fun getCharacteristic(
        serviceUUIDString: String, characteristicUUIDString: String
    ): BluetoothGattCharacteristic? {
        val serviceUUID = UUID.fromString(serviceUUIDString)
        val characteristicUUID = UUID.fromString(characteristicUUIDString)

        return bGatt?.getService(serviceUUID)?.getCharacteristic(characteristicUUID)
    }

    // Read from a Characteristic
    suspend fun readCharacteristic(characteristic: BluetoothGattCharacteristic?): BLEResult? {

        if (characteristic!= null && characteristic.isReadable()) {
            bGatt?.readCharacteristic(characteristic)

        } else error("Characteristic ${characteristic?.uuid} cannot be read")
        return waitForResult(characteristic.uuid.toString())
    }

    // Writes to a Characteristic
    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic?, payload: ByteArray) {
        val writeType = when {
            characteristic?.isWritable() == true -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic?.isWritableWithoutResponse() == true -> {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            else -> error("Characteristic ${characteristic?.uuid} cannot be written to")
        }

        bGatt?.let { gatt ->
            characteristic.writeType = writeType
            characteristic.value = payload
            gatt.writeCharacteristic(characteristic)
        } ?: error("Not connected to a BLE device!")

       // return waitForResult(characteristic.uuid.toString())
    }

    /** Notifications / Indications */

    suspend fun enableNotifications(characteristic: BluetoothGattCharacteristic?): BLEResult? {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        val payload = when {
            characteristic?.isIndicatable() == true -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic?.isNotifiable() == true -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> {
                Timber.e("${characteristic?.uuid} doesn't support notifications/indications")
                return null
            }
        }

        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (bGatt?.setCharacteristicNotification(characteristic, true) == false) {
                Timber.e("setCharacteristicNotification failed for ${characteristic.uuid}")
                return null
            }
            writeDescriptor(cccDescriptor, payload)
            return waitForResult(cccDescriptor.uuid.toString())
        } ?: Timber.e("${characteristic.uuid} doesn't contain the CCC descriptor!")
        return null
    }

    suspend fun disableNotifications(characteristic: BluetoothGattCharacteristic?): BLEResult? {
        if (characteristic != null) {
            if (!characteristic.isNotifiable() && !characteristic.isIndicatable()) {
                Timber.e("${characteristic.uuid} doesn't support indications/notifications")
                return null
            }
        }

        val cccdUUID = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic?.getDescriptor(cccdUUID)?.let { cccDescriptor ->
            if (bGatt?.setCharacteristicNotification(characteristic, false) == false) {
                Timber.e("setCharacteristicNotification failed for ${characteristic.uuid}")
                return null
            }
            writeDescriptor(cccDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            return waitForResult(cccDescriptor.uuid.toString())
        } ?: Timber.e("${characteristic?.uuid} doesn't contain the CCC descriptor")
        return null
    }

    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, payload: ByteArray) {
        bGatt?.let { gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
        } ?: error("Not connected to a BLE device!")
    }

    /** Bonding (Not in Use) */

    fun createBond(device: BluetoothDevice) {
        // Gatt.device
        device.createBond()
    }

    fun removeBond(device: BluetoothDevice) {
        try {
            val method = device.javaClass.getMethod("removeBond")
            val result: Boolean = method.invoke(device) as Boolean

            if (result) Timber.i("Successfully removed bond!")
        } catch (e: Exception) {
            Timber.e("Error: could not remove bond!")
        }
    }

    /** Helper Functions */

    fun hasPermissions(context: Context): Boolean {
        return hasLocationPermission(context) && hasBluetoothPermission(context)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun hasLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true

        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun BluetoothGattCharacteristic.isReadable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    fun BluetoothGattCharacteristic.isWritable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

    fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

    fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

    fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return properties and property != 0
    }

    // Wait for BLE Operation Result
    private suspend fun waitForResult(id: String): BLEResult? {
        return withTimeoutOrNull(TimeUnit.SECONDS.toMillis(5)) {
            var bleResult: BLEResult = channel.receive()
            while (bleResult.id != id) {
                bleResult = channel.receive()
            }
            bleResult
        } ?: run {
            //throw BLETimeoutException("BLE Operation Timed Out!")
            Timber.e("BLE Operation Timed Out!")
            return null
        }
    }
}
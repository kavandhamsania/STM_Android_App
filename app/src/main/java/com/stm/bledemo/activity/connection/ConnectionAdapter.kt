package com.stm.bledemo.activity.connection

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.content.res.AssetManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.stm.bledemo.R
import com.stm.bledemo.ble.BLEManager
import com.stm.bledemo.ble.YOUR_SERVICE_UUID
import com.stm.bledemo.databinding.RowConnectionBinding
import com.stm.bledemo.extension.hexToByteArray
import com.stm.bledemo.extension.removeWhiteSpace
import com.stm.bledemo.extension.toHexString
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.content.ContextCompat
import com.stm.bledemo.BLEApplication

val SERVICE_UUID = "D973F2E0-B19E-11E2-9E96-0800200C9A66"
val WRITE_CHARACTERISTIC_UUID = "D973F2E2-B19E-11E2-9E96-0800200C9A66"
val YOUR_NOTIFY_CHARACTERISTIC_UUID = "D973F2E1-B19E-11E2-9E96-0800200C9A66"
val ACKNOWLEDGMENT_FLAG = 0x01.toByte()
val acknowledgmentMutex = Mutex()
var isWaitingForAcknowledgment = false

@SuppressLint("NotifyDataSetChanged")
class ConnectionAdapter : RecyclerView.Adapter<ConnectionAdapter.ViewHolder>() {

    private val items: ArrayList<ConnectionItem> = arrayListOf()
    var rec =ByteArray(2)
    var recchar =ByteArray(2)


    @SuppressLint("MissingPermission")
    inner class ViewHolder(val binding: RowConnectionBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            with (binding) {
                // Read Button Clicked
                readButton.setOnClickListener {
                    BLEManager.scope.launch {
                        val item = items[bindingAdapterPosition]
                        var read= ByteArray(10)
                       BLEManager.readCharacteristic(item.characteristic)

                    }

                }

                // Write Button Clicked (Accepts Hex Message, Sends Byte Array)
                writeButton.setOnClickListener {
                    BLEManager.scope.launch {
                        if (writeEditText.text.isNotEmpty()) {
                            val item = items[bindingAdapterPosition]
                            val byteMessage = writeEditText.text.toString().removeWhiteSpace().hexToByteArray()
                            val service = BLEManager.bGatt?.getService(UUID.fromString("D973F2E0-B19E-11E2-9E96-0800200C9A66"))
                            val characteristic1 = service!!.getCharacteristic(UUID.fromString("D973F2E2-B19E-11E2-9E96-0800200C9A66"))
                        //    BLEManager.writeCharacteristic(item.characteristic, byteMessage)
                            val byteArray = readBinaryData(BLEApplication.app,"finite_state2.bin",characteristic1)
                            if (byteArray != null) {
                            val Meta_Data_Frame= MetaInfo( FIRMWARE_UPDATE, EXT_FW_START_ADDRESS,byteArray.size.toUInt(),0u)
                            val Header_Frame = ETXOTAHeader.headerdataframe(ETXOTAPacketType.ETX_OTA_PACKET_TYPE_HEADER,
                                0x0Du,Meta_Data_Frame,0u).HeaderByteArray()
                            val dataList= listOf(Start_Frame,Header_Frame)
                            val enddata= listOf(Stop_Frame)
                            val coroutineScope = CoroutineScope(Dispatchers.Main)
                            coroutineScope.launch {
                                  BLEManager.startDataTransmission(dataList)
                                  BLEManager.sendotadata(byteArray)
                                  BLEManager.startDataTransmission(enddata)

                                }
                            }
                        }



                        writeEditText.setText("")
                    }
                }

                // Notify Switch Toggled
                notifySwitch.setOnCheckedChangeListener { _, isChecked ->
                    BLEManager.scope.launch {
                        val item = items[bindingAdapterPosition]

                        if (isChecked) {
                            BLEManager.enableNotifications(item.characteristic)
                            item.notify = true
                        } else {
                            BLEManager.disableNotifications(item.characteristic)
                            item.notify = false
                        }
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = DataBindingUtil.inflate<RowConnectionBinding>(
            inflater,
            R.layout.row_connection,
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        with(holder.binding) {
            itemName.text = item.name

            if (item.indicatable) item.notifyLabel = "Indicate"
            notifySwitch.text = item.notifyLabel
            notifySwitch.isChecked = item.notify

            readLayout.isGone = !item.readable
            writeLayout.isGone = !(item.writable || item.writableNoResponse)
            notifyLayout.isGone = !(item.notifiable || item.indicatable)

            if (item.type == "Characteristic") {
                itemUUID.text = item.characteristic?.uuid
                    .toString()
                    .uppercase()
                    .substring(4, 8)
                readValue.text = item.characteristic?.value?.toHexString() ?: ""
                notifyValue.text = item.characteristic?.value?.toHexString() ?: ""
            } else {
                itemUUID.text = item.service?.uuid
                    .toString()
                    .uppercase()
                    .substring(4, 8)
            }
        }
    }

    override fun getItemCount() = items.size

    fun addItemList(itemList: List<ConnectionItem>) {
        items.addAll(itemList)
        notifyDataSetChanged()
    }

    fun valueUpdated(characteristic: BluetoothGattCharacteristic) {
        val index = items.indexOfFirst {
            it.characteristic != null && it.characteristic == characteristic
        }

        if (index != -1) {
            notifyItemChanged(index)
        }
    }
}

package com.stm.bledemo.activity.connection
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.res.AssetManager
import com.stm.bledemo.ble.BLEManager
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Constants
 val ETX_OTA_SOF: UByte = 0xAA.toUByte()
 val ETX_OTA_EOF: UByte = 0xBB.toUByte()
 val ETX_OTA_ACK: UByte = 0x00.toUByte()
 val ETX_OTA_NACK: UByte = 0x01.toUByte()

const val ETX_APP_FLASH_ADDR: UInt = 0x08040000U
const val ETX_OTA_DATA_MAX_SIZE: Int = 62
const val ETX_OTA_DATA_OVERHEAD: Int = 9
const val ETX_OTA_PACKET_MAX_SIZE: Int = ETX_OTA_DATA_MAX_SIZE + ETX_OTA_DATA_OVERHEAD
const val ETX_OTA_MAX_FW_SIZE: Int = 1024 * 512

const val FIRMWARE_UPDATE: UByte=0x01u
const val EXT_FW_START_ADDRESS: UInt= 0X1000u


// Enums
enum class ETXOTAEx {
    ETX_OTA_EX_OK,
    ETX_OTA_EX_ERR
}

enum class ETXOTAState {
    ETX_OTA_STATE_IDLE,
    ETX_OTA_STATE_START,
    ETX_OTA_STATE_HEADER,
    ETX_OTA_STATE_DATA,
    ETX_OTA_STATE_END
}

enum class ETXOTAPacketType  {
    ETX_OTA_PACKET_TYPE_CMD,
    ETX_OTA_PACKET_TYPE_DATA,
    ETX_OTA_PACKET_TYPE_HEADER,
    ETX_OTA_PACKET_TYPE_RESPONSE
}

enum class ETXOTACmd {
    ETX_OTA_CMD_START,
    ETX_OTA_CMD_END,
    ETX_OTA_CMD_ABORT
}

// Structures
data class MetaInfo(
    val firmwareUpdateFlag: UByte,
    val extStartAddress: UInt,
    val firmwareSize: UInt,
    val packageCrc: UInt
)

data class ETXOTACommand(
    val sof: UByte,
    val packetType: UByte,
    val dataLen: UShort,
    val cmd: UByte,
    val crc: UInt,
    val eof: UByte
)


data class ETXOTAData @OptIn(ExperimentalUnsignedTypes::class) constructor(
    val sof: UByte,
    val packetType: UByte,
    val dataLen: UShort,
    val data: UByteArray
)

data class ETXOTAResponse(
    val sof: UByte,
    val packetType: UByte,
    val dataLen: UShort,
    val status: UByte,
    val crc: UInt,
    val eof: UByte
)



class CmdDataFrame(
   private val startOfFrame: UByte,
   private val command: ETXOTAPacketType,
   private val length: UShort,
   private val otaData: UByte,
   private val crc: UInt,
   private val endOfFrame: UByte
) {

    companion object {
        const val CMD_START: UByte = 0x00u
        const val CMD_END: UByte = 0x01u
        const val CMD_HEADER: UByte = 0x03u
        const val OTA_PACKET_TYPE_CMD: UByte = 0x0u

        fun createDataFrame(
            commandtype: ETXOTAPacketType,
            length: UShort,
            otaData: UByte,
            crc: UInt
        ): CmdDataFrame {
            return CmdDataFrame(
                startOfFrame = ETX_OTA_SOF, // Assuming SOF is always 0xAA
                command = commandtype,
                length = length,
                otaData = otaData,
                crc = crc,
                endOfFrame = ETX_OTA_EOF // Assuming EOF is always 0x55
            )
        }

    }

    fun CmdByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(startOfFrame.toByte())
        buffer.put(command.ordinal.toByte())
        buffer.putShort(length.toShort())
        buffer.put(otaData.toByte())
        buffer.putInt(crc.toInt())
        buffer.put(endOfFrame.toByte())
        return buffer.array()

    }

}

 class ETXOTAHeader(
    val sof: UByte,
    val packetType: ETXOTAPacketType,
    val dataLen: UShort,
    val metaData: MetaInfo,
    val crc: UInt,
    val eof: UByte
)
{
    companion object{
        fun headerdataframe(
            packetType: ETXOTAPacketType,
            dataLen: UShort,
            metaData: MetaInfo,
            crc: UInt
        ): ETXOTAHeader {
            return ETXOTAHeader(
                sof = ETX_OTA_SOF, // Assuming SOF is always 0xAA
                packetType = packetType,
                dataLen = dataLen,
                metaData = metaData,
                crc = crc,
                eof = ETX_OTA_EOF // Assuming EOF is always 0x55
            )
        }
    }

    fun HeaderByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(sof.toByte())
        buffer.put(packetType.ordinal.toByte())
        buffer.putShort(dataLen.toShort())
        buffer.put(metaData.firmwareUpdateFlag.toByte())
        buffer.putInt(metaData.extStartAddress.toInt())
        buffer.putInt(metaData.firmwareSize.toInt())
        buffer.putInt(metaData.packageCrc.toInt())
        buffer.putInt(crc.toInt())
        buffer.put(eof.toByte())
        return buffer.array()

    }
}


class EXT_OTA_DATA(
    val sof: UByte,
    val packetType: ETXOTAPacketType,
    val dataLen: UShort,
    val payload: UByteArray,
    val crc: UInt,
    val eof: UByte

)
{
    companion object{

        fun send_ota_data( packetType: ETXOTAPacketType,
                           dataLen: UShort,
                           payload: UByteArray,
                           crc: UInt
                        ):EXT_OTA_DATA{
            return EXT_OTA_DATA(
                sof = ETX_OTA_SOF, // Assuming SOF is always 0xAA
                packetType = packetType,
                dataLen = dataLen,
                payload = payload,
                crc = crc,
                eof = ETX_OTA_EOF // Assuming EOF is always 0x55
                )

        }


    }

    fun OtaDataByteArray(dataLen: Short): ByteArray {
        val buffer = ByteBuffer.allocate(9 + dataLen ).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(sof.toByte())
        buffer.put(packetType.ordinal.toByte())
        buffer.putShort(dataLen.toShort())
        buffer.put(payload.toByteArray())
        buffer.putInt(crc.toInt())
        buffer.put(eof.toByte())
        return buffer.array()

    }


}



val Start_Frame = CmdDataFrame.createDataFrame(
    ETXOTAPacketType.ETX_OTA_PACKET_TYPE_CMD,
    0x01U, CmdDataFrame.CMD_START,0x00U).CmdByteArray()
val Stop_Frame =  CmdDataFrame.createDataFrame(
    ETXOTAPacketType.ETX_OTA_PACKET_TYPE_CMD,
    0x01U, CmdDataFrame.CMD_END,0x00U).CmdByteArray()

val Receive_ACK_frame = CmdDataFrame.createDataFrame(
    ETXOTAPacketType.ETX_OTA_PACKET_TYPE_RESPONSE,
    0x01u, ETX_OTA_ACK,0u).CmdByteArray()



fun readOneChunk(context: Context, fileName: String): String? {
    try {
        val assetManager: AssetManager = context.assets
        val inputStream = assetManager.open(fileName)

        val byteArray = ByteArray(400)
        val bytesRead = inputStream.read(byteArray)

        if (bytesRead != -1) {
            val hexStringBuilder = StringBuilder()

            // Convert the binary data to hexadecimal
            for (i in 0 until bytesRead) {
                hexStringBuilder.append(String.format("%02X", byteArray[i]))
            }

            inputStream.close()

            // Return the hexadecimal chunk
            return hexStringBuilder.toString()
        }

        inputStream.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return null
}

suspend fun readBinaryData(context: Context, fileName: String, writeCharacteristic: BluetoothGattCharacteristic): ByteArray? {
    try {
        val assetManager: AssetManager = context.assets
        val inputStream = assetManager.open(fileName)
        val fileLength = inputStream.available()

        val byteArray = ByteArray(fileLength)
        val bytesRead: Byte = inputStream.read(byteArray).toByte()
     //   BLEManager.writeCharacteristic(writeCharacteristic,chunk1)
 /************
        if (bytesRead > 0) {
            val hexStringBuilder = StringBuilder()

            // Convert the binary data to hexadecimal
            for (i in 0 until bytesRead) {
                hexStringBuilder.append(String.format("%02X", byteArray[i]))
            }
            // Return the hexadecimal data
            return hexStringBuilder.toString()

        }
************/
        return byteArray
        inputStream.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }

    return null
}

// Function to send data in chunks over BLE
/***********************************************
suspend fun sendHexChunksOverBLE(hexData: String?, chunkSize: Int, writeCharacteristic: BluetoothGattCharacteristic) {
    var offset = 0
    if (hexData != null) {
        while (offset < hexData.length) {
            val end = if (offset + chunkSize*2 < hexData.length) offset + chunkSize*2 else hexData.length
            val chunk = hexData.substring(offset, end)
            // Convert the chunk to bytes
            val bytes = hexStringToByteArray(chunk)
            // Write the chunk to BLE characteristic
            //   writeCharacteristic.value = bytes
            BLEManager.writeCharacteristic(writeCharacteristic, bytes)
            offset += chunkSize
        }
    }
/*
    // Handle the remaining data
    if (offset < hexData.length) {
        val remainingChunk = hexData.substring(offset)
        // Convert the remaining chunk to bytes
        val remainingBytes = hexStringToByteArray(remainingChunk)

        // Write the remaining chunk to BLE characteristic
        BLEManager.writeCharacteristic(writeCharacteristic, remainingBytes)
}
*/
// Function to convert hexadecimal string to byte array
}
fun hexStringToByteArray(hexString: String): ByteArray {
    val result = ByteArray(hexString.length / 2)
    for (i in 0 until hexString.length step 2) {
        val byte = ((Character.digit(hexString[i], 16) shl 4) or Character.digit(hexString[i + 1], 16)).toByte()
        result[i / 2] = byte
    }
    return result
}
 **********************************/
package org.siros.wwwallet.debug

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import org.siros.wwwallet.bluetooth.toHumanReadable
import timber.log.Timber

open class PrintingBluetoothGattServerCallback : BluetoothGattServerCallback() {
    override fun onConnectionStateChange(
        device: BluetoothDevice?,
        status: Int,
        newState: Int,
    ) {
        Timber.d("onConnectionStateChange: $device, ${status.human}, ${newState.human}")

        super.onConnectionStateChange(device, status, newState)
    }

    override fun onServiceAdded(
        status: Int,
        service: BluetoothGattService?,
    ) {
        Timber.d("onServiceAdded: $status, $service")

        super.onServiceAdded(status, service)
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice?,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic?,
    ) {
        Timber.d("onCharacteristicReadRequest: $device, $requestId, $offset, $characteristic")

        super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice?,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic?,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
    ) {
        Timber.d("onCharacteristicWriteRequest: $device, $requestId, $characteristic, $preparedWrite, $responseNeeded, $offset, ${value.toHumanReadable()}")

        super.onCharacteristicWriteRequest(
            device,
            requestId,
            characteristic,
            preparedWrite,
            responseNeeded,
            offset,
            value,
        )
    }

    override fun onDescriptorReadRequest(
        device: BluetoothDevice?,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor?,
    ) {
        Timber.d("onDescriptorReadRequest: $device, $requestId, $offset, $descriptor")

        super.onDescriptorReadRequest(device, requestId, offset, descriptor)
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice?,
        requestId: Int,
        descriptor: BluetoothGattDescriptor?,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
    ) {
        Timber.d(
            "onDescriptorWriteRequest: $device, $requestId, $descriptor, $preparedWrite, $responseNeeded, $offset, ${value.toHumanReadable()}",
        )

        super.onDescriptorWriteRequest(
            device,
            requestId,
            descriptor,
            preparedWrite,
            responseNeeded,
            offset,
            value,
        )
    }

    override fun onExecuteWrite(
        device: BluetoothDevice?,
        requestId: Int,
        execute: Boolean,
    ) {
        Timber.d("onExecuteWrite: $device, $requestId, $execute")

        super.onExecuteWrite(device, requestId, execute)
    }

    override fun onNotificationSent(
        device: BluetoothDevice?,
        status: Int,
    ) {
        Timber.d("onNotificationSent: $device, $status")

        super.onNotificationSent(device, status)
    }

    override fun onMtuChanged(
        device: BluetoothDevice?,
        mtu: Int,
    ) {
        Timber.d("onMtuChanged: $device, $mtu")

        super.onMtuChanged(device, mtu)
    }

    override fun onPhyUpdate(
        device: BluetoothDevice?,
        txPhy: Int,
        rxPhy: Int,
        status: Int,
    ) {
        Timber.d("onPhyUpdate: $device, $txPhy, $rxPhy, $status")

        super.onPhyUpdate(device, txPhy, rxPhy, status)
    }

    override fun onPhyRead(
        device: BluetoothDevice?,
        txPhy: Int,
        rxPhy: Int,
        status: Int,
    ) {
        Timber.d("onPhyRead $device, $txPhy, $rxPhy, $status")

        super.onPhyRead(device, txPhy, rxPhy, status)
    }
}

private val Int.human: String
    get() =
        when (this) {
            STATE_DISCONNECTED -> "STATE_DISCONNECTED"
            STATE_CONNECTING -> "STATE_CONNECTING"
            STATE_CONNECTED -> "STATE_CONNECTED"
            STATE_DISCONNECTING -> "STATE_DISCONNECTING"
            else -> "unknown"
        }

/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.pandora

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

import com.google.protobuf.Empty
import com.google.protobuf.ByteString

import io.grpc.Status
import io.grpc.stub.StreamObserver

import java.util.UUID

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn

import pandora.GATTGrpc.GATTImplBase
import pandora.GattProto.*

@kotlinx.coroutines.ExperimentalCoroutinesApi
class Gatt(private val context: Context) : GATTImplBase() {
  private val TAG = "PandoraGatt"

  private val mScope: CoroutineScope
  private val flow: Flow<Intent>

  private val mBluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
  private val mBluetoothAdapter = mBluetoothManager.adapter

  init {
    mScope = CoroutineScope(Dispatchers.Default)

    val intentFilter = IntentFilter()
    intentFilter.addAction(BluetoothDevice.ACTION_UUID)

    flow = intentFlow(context, intentFilter).shareIn(mScope, SharingStarted.Eagerly)
  }

  fun deinit() {
    mScope.cancel()
  }

  override fun exchangeMTU(request: ExchangeMTURequest,
      responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(mScope, responseObserver) {
      val mtu = request.mtu
      Log.i(TAG, "exchangeMTU MTU=$mtu")
      if (!GattInstance.get(request.connection.cookie).mGatt.requestMtu(mtu)) {
        Log.e(TAG, "Error on requesting MTU $mtu")
        throw Status.UNKNOWN.asException()
      }
      Empty.getDefaultInstance()
    }
  }

  override fun writeCharacteristicFromHandle(request: WriteCharacteristicRequest,
      responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(mScope, responseObserver) {
      val gattInstance = GattInstance.get(request.connection.cookie)
      val characteristic: BluetoothGattCharacteristic? =
          getCharacteristicWithHandle(request.handle, gattInstance)
      if (characteristic != null) {
        Log.i(TAG, "writeCharacteristicFromHandle handle=${request.handle}")
        gattInstance.mGatt.writeCharacteristic(characteristic,
            request.value.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
      } else {
        Log.e(TAG, "Characteristic handle ${request.handle} not found.")
        throw Status.UNKNOWN.asException()
      }
      Empty.getDefaultInstance()
    }
  }

  override fun discoverServiceByUuid(request: DiscoverServiceByUuidRequest,
      responseObserver: StreamObserver<DiscoverServicesResponse>) {
    grpcUnary<DiscoverServicesResponse>(mScope, responseObserver) {
      val gattInstance = GattInstance.get(request.connection.cookie)
      Log.i(TAG, "discoverServiceByUuid uuid=${request.uuid}")
      // In some cases, GATT starts a discovery immediately after being connected, so
      // we need to wait until the service discovery is finished to be able to discover again.
      // This takes between 20s and 28s, and there is no way to know if the service is busy or not.
      // Delay was originally 30s, but due to flakyness increased to 32s.
      delay(32000L)
      check(gattInstance.mGatt.discoverServiceByUuid(UUID.fromString(request.uuid)))
      // BluetoothGatt#discoverServiceByUuid does not trigger any callback and does not return
      // any service, the API was made for PTS testing only.
      DiscoverServicesResponse.newBuilder().build()
    }
  }

  override fun discoverServices(request: DiscoverServicesRequest,
      responseObserver: StreamObserver<DiscoverServicesResponse>) {
    grpcUnary<DiscoverServicesResponse>(mScope, responseObserver) {
      Log.i(TAG, "discoverServices")
      val gattInstance = GattInstance.get(request.connection.cookie)
      check(gattInstance.mGatt.discoverServices())
      gattInstance.waitForDiscoveryEnd()
      DiscoverServicesResponse.newBuilder()
          .addAllServices(generateServicesList(gattInstance.mGatt.services, 1)).build()
    }
  }

  override fun discoverServicesSdp(request: DiscoverServicesSdpRequest,
      responseObserver: StreamObserver<DiscoverServicesSdpResponse>) {
    grpcUnary<DiscoverServicesSdpResponse>(mScope, responseObserver) {
      Log.i(TAG, "discoverServicesSdp")
      val bluetoothDevice = request.address.toBluetoothDevice(mBluetoothAdapter)
      check(bluetoothDevice.fetchUuidsWithSdp())
      flow
        .filter { it.getAction() == BluetoothDevice.ACTION_UUID }
        .filter { it.getBluetoothDeviceExtra() == bluetoothDevice }
        .first()
      val uuidsList = arrayListOf<String>()
      for (parcelUuid in bluetoothDevice.getUuids()) {
        uuidsList.add(parcelUuid.toString())
      }
      DiscoverServicesSdpResponse.newBuilder()
          .addAllServiceUuids(uuidsList).build()
    }
  }

  override fun clearCache(request: ClearCacheRequest,
      responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(mScope, responseObserver) {
      Log.i(TAG, "clearCache")
      val gattInstance = GattInstance.get(request.connection.cookie)
      check(gattInstance.mGatt.refresh())
      Empty.getDefaultInstance()
    }
  }

  override fun readCharacteristicFromHandle(request: ReadCharacteristicRequest,
      responseObserver: StreamObserver<ReadCharacteristicResponse>) {
    grpcUnary<ReadCharacteristicResponse>(mScope, responseObserver) {
      Log.i(TAG, "readCharacteristicFromHandle handle=${request.handle}")
      val gattInstance = GattInstance.get(request.connection.cookie)
      val characteristic: BluetoothGattCharacteristic? =
          getCharacteristicWithHandle(request.handle, gattInstance)
      val readValue: GattInstance.GattInstanceValueRead?
      checkNotNull(characteristic) {
        "Characteristic handle ${request.handle} not found."
      }
      readValue = gattInstance.readCharacteristicBlocking(characteristic)
      ReadCharacteristicResponse.newBuilder()
          .setStatus(AttStatusCode.forNumber(readValue.status))
          .setValue(ByteString.copyFrom(readValue.value)).build()
    }
  }

  override fun readCharacteristicFromUuid(request: ReadCharacteristicFromUuidRequest,
      responseObserver: StreamObserver<ReadCharacteristicResponse>) {
    grpcUnary<ReadCharacteristicResponse>(mScope, responseObserver) {
      Log.i(TAG, "readCharacteristicFromUuid uuid=${request.uuid}")
      val gattInstance = GattInstance.get(request.connection.cookie)
      tryDiscoverServices(gattInstance)
      val readValue = gattInstance.readCharacteristicUuidBlocking(UUID.fromString(request.uuid),
          request.startHandle, request.endHandle)
      ReadCharacteristicResponse.newBuilder()
          .setStatus(AttStatusCode.forNumber(readValue.status))
          .setValue(ByteString.copyFrom(readValue.value)).build()
    }
  }

  override fun readCharacteristicDescriptorFromHandle(request: ReadCharacteristicDescriptorRequest,
      responseObserver: StreamObserver<ReadCharacteristicDescriptorResponse>) {
    grpcUnary<ReadCharacteristicDescriptorResponse>(mScope, responseObserver) {
      Log.i(TAG, "readCharacteristicDescriptorFromHandle handle=${request.handle}")
      val gattInstance = GattInstance.get(request.connection.cookie)
      val descriptor: BluetoothGattDescriptor? =
          getDescriptorWithHandle(request.handle, gattInstance)
      val readValue: GattInstance.GattInstanceValueRead?
      checkNotNull(descriptor) {
        "Descriptor handle ${request.handle} not found."
      }
      readValue = gattInstance.readDescriptorBlocking(descriptor)
      ReadCharacteristicDescriptorResponse.newBuilder()
        .setStatus(AttStatusCode.forNumber(readValue.status))
        .setValue(ByteString.copyFrom(readValue.value)).build()
    }
  }

  /**
   * Discovers services, then returns characteristic with given handle.
   * BluetoothGatt API is package-private so we have to redefine it here.
   */
  private suspend fun getCharacteristicWithHandle(handle: Int,
      gattInstance: GattInstance): BluetoothGattCharacteristic? {
    tryDiscoverServices(gattInstance)
    for (service: BluetoothGattService in gattInstance.mGatt.services.orEmpty()) {
      for (characteristic: BluetoothGattCharacteristic in service.characteristics) {
        if (characteristic.instanceId == handle) {
          return characteristic
        }
      }
    }
    return null
  }

  /**
   * Discovers services, then returns descriptor with given handle.
   * BluetoothGatt API is package-private so we have to redefine it here.
   */
  private suspend fun getDescriptorWithHandle(handle: Int,
      gattInstance: GattInstance): BluetoothGattDescriptor? {
    tryDiscoverServices(gattInstance)
    for (service: BluetoothGattService in gattInstance.mGatt.services.orEmpty()) {
      for (characteristic: BluetoothGattCharacteristic in service.characteristics) {
        for (descriptor: BluetoothGattDescriptor in characteristic.descriptors) {
          if (descriptor.getInstanceId() == handle) {
            return descriptor
          }
        }
      }
    }
    return null
  }

  /**
   * Generates a list of GattService from a list of BluetoothGattService.
   */
  private fun generateServicesList(servicesList: List<BluetoothGattService>, dpth: Int)
      : ArrayList<GattService> {
    val newServicesList = arrayListOf<GattService>()
    for (service in servicesList) {
      val serviceBuilder = GattService.newBuilder()
          .setHandle(service.getInstanceId())
          .setType(service.getType())
          .setUuid(service.getUuid().toString())
          .addAllIncludedServices(generateServicesList(service.getIncludedServices(), dpth+1))
          .addAllCharacteristics(generateCharacteristicsList(service.characteristics))
      newServicesList.add(serviceBuilder.build())
    }
    return newServicesList
  }

  /**
   * Generates a list of GattCharacteristic from a list of BluetoothGattCharacteristic.
   */
  private fun generateCharacteristicsList(characteristicsList : List<BluetoothGattCharacteristic>)
      : ArrayList<GattCharacteristic> {
    val newCharacteristicsList = arrayListOf<GattCharacteristic>()
    for (characteristic in characteristicsList) {
      val characteristicBuilder = GattCharacteristic.newBuilder()
          .setProperties(characteristic.getProperties())
          .setPermissions(characteristic.getPermissions())
          .setUuid(characteristic.getUuid().toString())
          .addAllDescriptors(generateDescriptorsList(characteristic.getDescriptors()))
          .setHandle(characteristic.getInstanceId())
      newCharacteristicsList.add(characteristicBuilder.build())
    }
    return newCharacteristicsList
  }

  /**
   * Generates a list of GattDescriptor from a list of BluetoothGattDescriptor.
   */
  private fun generateDescriptorsList(descriptorsList : List<BluetoothGattDescriptor>)
      : ArrayList<GattDescriptor> {
    val newDescriptorsList = arrayListOf<GattDescriptor>()
    for (descriptor in descriptorsList) {
      val descriptorBuilder = GattDescriptor.newBuilder()
          .setHandle(descriptor.getInstanceId())
          .setPermissions(descriptor.getPermissions())
          .setUuid(descriptor.getUuid().toString())
      newDescriptorsList.add(descriptorBuilder.build())
    }
    return newDescriptorsList
  }

  private suspend fun tryDiscoverServices(gattInstance: GattInstance) {
    if (!gattInstance.servicesDiscovered() && !gattInstance.mGatt.discoverServices()) {
      Log.e(TAG, "Error on discovering services for $gattInstance")
      throw Status.UNKNOWN.asException()
    } else {
      gattInstance.waitForDiscoveryEnd()
    }
  }
}
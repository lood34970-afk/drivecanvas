package com.example.drivecanvas

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Vehicle speed from AAOS PERF_VEHICLE_SPEED, converted to km/h. The value stays null until a reading
 * arrives, and also when the Car API is missing (non-automotive device) or CAR_SPEED is not granted.
 * Callers treat null as parked.
 */
class VehicleMotion {
    private val speed = MutableStateFlow<Float?>(null)
    val speedKmh: StateFlow<Float?> = speed.asStateFlow()

    private var subscription: AaosSpeedSubscription? = null

    /** Subscribes once. Safe to call again, e.g. after CAR_SPEED is granted. */
    fun start(context: Context) {
        if (subscription != null) return
        runCatching {
            AaosSpeedSubscription.open(context.applicationContext) { kmh -> speed.value = kmh }
        }.onSuccess {
            subscription = it
        }.onFailure {
            Log.i(TAG, "AAOS speed unavailable: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun stop() {
        runCatching { subscription?.close() }
        subscription = null
        speed.value = null
    }

    private companion object {
        const val TAG = "DriveCanvasVehicle"
    }
}

/**
 * All android.car types live here, so [VehicleMotion] itself never touches them. Only [open] uses
 * them, and it is only called inside runCatching.
 */
private class AaosSpeedSubscription private constructor(
    private val car: Car,
    private val manager: CarPropertyManager,
    private val callback: CarPropertyManager.CarPropertyEventCallback,
) {
    fun close() {
        runCatching { manager.unregisterCallback(callback) }
        runCatching { car.disconnect() }
    }

    companion object {
        fun open(context: Context, onSpeedKmh: (Float) -> Unit): AaosSpeedSubscription {
            val car = Car.createCar(context) ?: error("Car service unavailable")
            val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                ?: error("CarPropertyManager unavailable")
            val callback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val metersPerSecond = value.value as? Float ?: return
                    onSpeedKmh(metersPerSecond * 3.6f)
                }

                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    Log.w("DriveCanvasVehicle", "AAOS property error: property=$propertyId area=$areaId")
                }
            }
            val registered = manager.registerCallback(
                callback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_ONCHANGE,
            )
            check(registered) { "PERF_VEHICLE_SPEED callback not registered" }
            return AaosSpeedSubscription(car, manager, callback)
        }
    }
}

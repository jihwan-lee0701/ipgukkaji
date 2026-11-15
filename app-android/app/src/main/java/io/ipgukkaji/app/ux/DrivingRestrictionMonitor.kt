package io.ipgukkaji.app.ux

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log

/**
 * AAOS 표준 CarUxRestrictionsManager 로 주행 규제 상태를 감시한다 (Pleos 주행 규제 개발 가이드).
 * isRequiresDistractionOptimization() 이 true 면 앱은 사진·목록·선택을 숨기고 한 줄 상태만 보여준다.
 *
 * Car 서비스에 연결할 수 없으면(비차량 환경 등) 안전 쪽으로 판단해 "규제 중"으로 처리한다.
 */
class DrivingRestrictionMonitor(
    private val context: Context,
    private val onChanged: (restricted: Boolean) -> Unit,
) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        try {
            val c = Car.createCar(context)
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            manager = m
            m.registerListener { r -> onChanged(isRestricted(r)) }
            onChanged(isRestricted(m.currentCarUxRestrictions))
        } catch (t: Throwable) {
            Log.e(TAG, "CarUxRestrictionsManager 연결 실패 — 안전을 위해 규제 상태로 처리", t)
            onChanged(true)
        }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }

    private fun isRestricted(r: CarUxRestrictions?): Boolean {
        if (r == null) return true
        Log.i(TAG, "UX restrictions: DO=${r.isRequiresDistractionOptimization} active=${r.activeRestrictions}")
        return r.isRequiresDistractionOptimization
    }

    companion object {
        const val TAG = "Ipgukkaji/UxR"
    }
}

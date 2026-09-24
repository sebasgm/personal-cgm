package dev.cgm.app.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * The glucose value on a car screen.
 *
 * Android Auto only accepts five app categories — navigation, points of interest,
 * internet of things, weather and media — and a glucose monitor is none of them.
 * The usual workaround is to register as a *media* app so the value appears in
 * the media tab, which works but takes over the media surface: you get glucose or
 * you get music, not both, and the car offers play and pause for a number.
 *
 * This declares **IOT** instead. A sensor reporting to the phone is a connected
 * device, which is the closest honest fit, and since this is sideloaded there is
 * no store review to satisfy — only Android Auto's developer mode, which has to
 * be switched on with unknown sources allowed.
 *
 * It shows and never controls. Nothing here is tappable, which is also what the
 * category's own rules require while driving.
 */
class CgmCarAppService : CarAppService() {

    /**
     * Debug builds accept any host.
     *
     * A release build should name Google's signatures instead. This app is
     * sideloaded onto one phone, and a validator that rejects the only host it
     * will ever meet is a debugging afternoon for no gain.
     */
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = CgmSession()
}

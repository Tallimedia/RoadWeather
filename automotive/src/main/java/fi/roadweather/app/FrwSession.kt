package fi.roadweather.app

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class FrwSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = MainScreen(carContext)
}

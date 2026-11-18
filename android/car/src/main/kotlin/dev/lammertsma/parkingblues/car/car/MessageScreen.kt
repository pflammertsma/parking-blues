package dev.lammertsma.parkingblues.car.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/**
 * A message to the driver on a screen of the app's own, for things that went wrong or are not
 * possible. Used instead of a CarToast: it stays until the driver has read it, and a driver glancing
 * at a car screen may well miss a toast.
 */
class MessageScreen(
    carContext: CarContext,
    private val title: String,
    private val message: String,
) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(message)
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("OK")
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
}

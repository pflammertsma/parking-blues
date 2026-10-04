package dev.lammertsma.parkingblues.car.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle

/** Debug builds only: development switches that real users never see. */
class DeveloperScreen(
    carContext: CarContext,
    private val testLocationEnabled: Boolean,
    private val onTestLocationChanged: (Boolean) -> Unit,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Use test location")
                    .addText("Simulated driving in Zurich")
                    .setToggle(
                        Toggle.Builder { enabled ->
                            onTestLocationChanged(enabled)
                            screenManager.pop()
                        }.setChecked(testLocationEnabled).build()
                    )
                    .build()
            )
            .build()
        return ListTemplate.Builder()
            .setTitle("Developer options")
            .setHeaderAction(Action.BACK)
            .setSingleList(list)
            .build()
    }
}

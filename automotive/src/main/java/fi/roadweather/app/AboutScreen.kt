package fi.roadweather.app

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * Attribution, pushed from an ⓘ action rather than living as a tab.
 *
 * v0.4.x had this as the third tab; v0.5 moved it here so Warnings could take
 * that slot (`SPEC.md` §1). It is the app's **first pushed `Screen`** — every
 * template before this was drawn by [MainScreen] switching on its own
 * `activeTab`. The host supplies the back chevron automatically once the
 * [androidx.car.app.ScreenManager] stack holds more than one screen; nothing
 * here has to draw one.
 *
 * Content is unchanged from the old About tab — a flat list, not the
 * `MessageTemplate` `SPEC.md` §4 originally sketched. Changing the template
 * type was not part of this move and stays out of scope for it.
 */
class AboutScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.about_title))
                    .addText(BuildConfig.VERSION_NAME)
                    .build(),
            )
            // Attribution in the app, not only in the store listing. Both
            // licences require it.
            .addItem(
                Row.Builder()
                    .setTitle("Data")
                    .addText(carContext.getString(R.string.about_sources))
                    .build(),
            )
            .addItem(
                Row.Builder()
                    .setTitle("—")
                    .addText(carContext.getString(R.string.about_independent))
                    .build(),
            )
            .addItem(
                Row.Builder()
                    .setTitle("—")
                    .addText(carContext.getString(R.string.about_safety))
                    .build(),
            )
        return ListTemplate.Builder()
            .setSingleList(items.build())
            .setHeaderAction(androidx.car.app.model.Action.BACK)
            .build()
    }
}

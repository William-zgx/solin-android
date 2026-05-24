package com.bytedance.zgx.pocketmind.action

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings

class ActionExecutor(
    private val context: Context,
) {
    fun executeConfirmed(draft: ActionDraft): Boolean {
        val intent = when (draft.functionName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS ->
                Intent(Settings.ACTION_WIFI_SETTINGS)

            MobileActionFunctions.SEARCH_MAPS ->
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=${Uri.encode(draft.parameters["query"].orEmpty())}"),
                )

            MobileActionFunctions.COMPOSE_EMAIL ->
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_SUBJECT, draft.parameters["subject"].orEmpty())
                    putExtra(Intent.EXTRA_TEXT, draft.parameters["body"].orEmpty())
                }

            MobileActionFunctions.CREATE_CALENDAR_EVENT ->
                Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, draft.parameters["title"].orEmpty())
                    putExtra(CalendarContract.Events.DESCRIPTION, draft.parameters["description"].orEmpty())
                }

            MobileActionFunctions.CREATE_CONTACT_DRAFT ->
                Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.NAME, draft.parameters["name"].orEmpty())
                    putExtra(ContactsContract.Intents.Insert.EMAIL, draft.parameters["email"].orEmpty())
                    putExtra(ContactsContract.Intents.Insert.PHONE, draft.parameters["phone"].orEmpty())
                }

            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS ->
                Intent(Settings.ACTION_SETTINGS)

            else -> return false
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(intent)
        }.isSuccess
    }
}

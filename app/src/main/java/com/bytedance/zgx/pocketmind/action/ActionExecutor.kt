package com.bytedance.zgx.pocketmind.action

import android.app.SearchManager
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
        val intents = when (draft.functionName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS ->
                listOf(Intent(Settings.ACTION_WIFI_SETTINGS))

            MobileActionFunctions.SEARCH_MAPS ->
                listOf(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=${Uri.encode(draft.parameters["query"].orEmpty())}"),
                    ),
                )

            MobileActionFunctions.WEB_SEARCH ->
                webSearchIntents(draft.parameters["query"].orEmpty())

            MobileActionFunctions.COMPOSE_EMAIL ->
                listOf(
                    Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_SUBJECT, draft.parameters["subject"].orEmpty())
                        putExtra(Intent.EXTRA_TEXT, draft.parameters["body"].orEmpty())
                    },
                )

            MobileActionFunctions.CREATE_CALENDAR_EVENT ->
                listOf(
                    Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, draft.parameters["title"].orEmpty())
                        putExtra(CalendarContract.Events.DESCRIPTION, draft.parameters["description"].orEmpty())
                    },
                )

            MobileActionFunctions.CREATE_CONTACT_DRAFT ->
                listOf(
                    Intent(ContactsContract.Intents.Insert.ACTION).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.NAME, draft.parameters["name"].orEmpty())
                        putExtra(ContactsContract.Intents.Insert.EMAIL, draft.parameters["email"].orEmpty())
                        putExtra(ContactsContract.Intents.Insert.PHONE, draft.parameters["phone"].orEmpty())
                    },
                )

            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS ->
                listOf(Intent(Settings.ACTION_SETTINGS))

            else -> return false
        }

        return intents.any { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }

    private fun webSearchIntents(query: String): List<Intent> =
        listOf(
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
            },
            Intent(
                Intent.ACTION_VIEW,
                Uri.Builder()
                    .scheme("https")
                    .authority("www.google.com")
                    .path("search")
                    .appendQueryParameter("q", query)
                    .build(),
            ),
        )
}

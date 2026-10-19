package io.github.inkee1.memoalarmbridge.core

import java.net.URI

data class ValidatedUrl(val value:String)
const val CHATGPT_HOME="https://chatgpt.com/"
enum class LaunchOutcome {OPENED,NO_SAVED_LINK,NO_HANDLER}

fun validateConversationUrl(value:String):ValidatedUrl?=runCatching {
    require(value.length in 1..256&&value.none{it.isWhitespace()||it.isISOControl()})
    val uri=URI(value)
    require(uri.scheme=="https"&&uri.host=="chatgpt.com"&&uri.rawAuthority=="chatgpt.com"&&uri.userInfo==null&&uri.port==-1&&uri.rawQuery==null&&uri.rawFragment==null)
    require(Regex("^/c/[A-Za-z0-9_-]{1,128}$").matches(uri.rawPath))
    ValidatedUrl(uri.toASCIIString())
}.getOrNull()

/** Caller supplies an explicit user-pressed opener; no automatic fallback navigation. */
fun openConversation(value:String,open:(String)->Boolean):LaunchOutcome {
    val saved=validateConversationUrl(value)?:return LaunchOutcome.NO_SAVED_LINK
    return if(open(saved.value))LaunchOutcome.OPENED else LaunchOutcome.NO_HANDLER
}

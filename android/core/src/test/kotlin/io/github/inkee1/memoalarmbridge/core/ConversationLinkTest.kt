package io.github.inkee1.memoalarmbridge.core

import org.junit.Assert.*
import org.junit.Test

class ConversationLinkTest {
    private val url="https://chatgpt.com/c/11111111-1111-4111-8111-111111111111"
    @Test fun allowsSavedConversationUrl(){assertEquals(url,validateConversationUrl(url)?.value);var opened="";assertEquals(LaunchOutcome.OPENED,openConversation(url){opened=it;true});assertEquals(url,opened)}
    @Test fun rejectsLookalikeHostCredentialsQueryFragment(){for(value in listOf("http://chatgpt.com/c/abc","https://chatgpt.com.example.com/c/abc","https://example.com@chatgpt.com/c/abc","https://chatgpt.com/c/abc?token=value","https://chatgpt.com/c/abc#start","https://chatgpt.com:8443/c/abc","https://chatgpt.com/c/a%2fb","https://chatgpt.com/c/../settings","https://chatgpt.com/share/abc","javascript:alert(1)","https://chatgpt.com/c/a\n"))assertNull(value,validateConversationUrl(value))}
    @Test fun handlesNoSavedUrl(){assertEquals(LaunchOutcome.NO_SAVED_LINK,openConversation(""){throw AssertionError("unexpected launch")});assertNull(validateConversationUrl(""))}
    @Test fun missingHandlerReturnsFallback(){assertEquals(LaunchOutcome.NO_HANDLER,openConversation(url){false});assertEquals("https://chatgpt.com/",CHATGPT_HOME)}
}

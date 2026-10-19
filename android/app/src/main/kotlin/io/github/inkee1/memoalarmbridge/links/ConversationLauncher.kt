package io.github.inkee1.memoalarmbridge.links

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.inkee1.memoalarmbridge.core.*

object ConversationLauncher {
    private fun preferences(context:Context)=context.getSharedPreferences("conversation-link",Context.MODE_PRIVATE)
    fun saved(context:Context):String=preferences(context).getString("url","").orEmpty()
    fun save(context:Context,value:String){
        val clean=if(value.isBlank())""else requireNotNull(validateConversationUrl(value)){"Use your own https://chatgpt.com/c/... link without query or fragment"}.value
        check(preferences(context).edit().putString("url",clean).commit()){"Link could not be saved"}
    }
    private fun open(context:Context,url:String):Boolean=runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true}.getOrDefault(false)
    fun launch(context:Context):LaunchOutcome=openConversation(saved(context)){open(context,it)}
    fun home(context:Context):Boolean=open(context,CHATGPT_HOME)
}

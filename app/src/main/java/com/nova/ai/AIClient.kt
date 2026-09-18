package com.nova.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Local-only AI facade. There are deliberately no cloud API providers in this build. */
object AIClient {
    data class PlannedAction(val action: String, val payload: String)
    data class RouteDecision(val type: String, val action: String, val payload: String, val response: String,
                             val plan: List<PlannedAction> = emptyList(), val requiresConfirmation: Boolean = false,
                             val goal: String = "", val progress: String = "", val stateSummary: String = "",
                             val confirmationPrompt: String = "This action needs confirmation. Say yes to confirm, or say cancel.")
    data class OpenRouterModel(val id: String, val name: String)
    interface AIResponseCallback { fun onSuccess(reply: String); fun onError(message: String) }
    interface RouteCallback { fun onSuccess(decision: RouteDecision); fun onError(message: String) }
    interface ModelsCallback { fun onSuccess(models: List<OpenRouterModel>); fun onError(message: String) }

    private val main = Handler(Looper.getMainLooper())

    fun sendMessage(provider: String, apiKey: String, conversationHistory: List<Map<String,String>>, endpoint: String = "", modelName: String = "", callback: AIResponseCallback) {
        Thread { try { val prompt = conversationHistory.takeLast(18).joinToString("\n") { "${it["role"]}: ${it["content"]}" }; val r=LocalAIEngine.complete(AppContext.get(), prompt); main.post{callback.onSuccess(r)} } catch(e:Exception){main.post{callback.onError(e.message ?: "Local AI error")}} }.start()
    }

    fun routeRequest(provider:String, apiKey:String, conversationHistory:List<Map<String,String>>, currentText:String, endpoint:String="", modelName:String="", callback:RouteCallback) {
        Thread { try { val prompt = "USER COMMAND:\n$currentText\n\nRECENT CONVERSATION:\n${conversationHistory.takeLast(12).joinToString("\n") { "${it["role"]}: ${it["content"]}" }}"; val d=parseDecision(LocalAIEngine.complete(AppContext.get(),prompt,500)); main.post{callback.onSuccess(d)} } catch(e:Exception){main.post{callback.onError(e.message ?: "Local AI error")}} }.start()
    }

    fun routeAgentRequest(provider:String="", apiKey:String="", conversationHistory:List<Map<String,String>>, task:String, screenSnapshot:String, previousResult:String="", actionHistory:List<String> = emptyList(), taskState:String="", contextValues:Map<String,String> = emptyMap(), memoryHints:String="", endpoint:String="", modelName:String="", callback:RouteCallback) {
        Thread { try {
            val prompt="""USER TASK:\n$task\n\nCURRENT UI:\n$screenSnapshot\n\nTASK STATE:\n$taskState\n\nCONTEXT:\n${contextValues.entries.joinToString("\n") { "${it.key}=${it.value}" }}\n\nPREVIOUS RESULT:\n$previousResult\n\nACTION HISTORY:\n${actionHistory.takeLast(12).joinToString("\n")}\n\nWORKFLOW HINTS:\n$memoryHints\n\nChoose exactly ONE next action. Use FINISH only when the task is verified complete."""
            val d=parseDecision(LocalAIEngine.complete(AppContext.get(),prompt,700)); main.post{callback.onSuccess(d)}
        } catch(e:Exception){main.post{callback.onError(e.message ?: "Local AI planning error")}} }.start()
    }

    fun getOpenRouterModels(apiKey:String, callback:ModelsCallback) = main.post { callback.onError("Cloud model providers are disabled in this Nova build. The local model is selected in Settings.") }

    private fun payloadFor(action:String, data:JSONObject):String {
        val a=action.uppercase(); return when(a){
            "OPEN_APP" -> data.optString("name")
            "SEARCH_APP" -> data.optString("name")+"|"+data.optString("query")
            "PLAY_STORE_SEARCH","YOUTUBE_SEARCH","WEB_SEARCH" -> data.optString("query")
            "OPEN_URL" -> data.optString("url")
            "DIAL","CALL" -> data.optString("contactOrNumber", data.optString("number"))
            "SMS" -> data.optString("number")+"|"+data.optString("message")
            "MAPS" -> data.optString("destination")
            "ALARM" -> data.optString("time24h")
            "SET_TIMER" -> data.optString("seconds")
            "CALENDAR_EVENT" -> data.toString()
            "QUERY_CONTACT","LIST_APPS","READ_TEXT","CLICK","CLICK_DESCRIPTION","CLICK_ID","LONG_CLICK","FOCUS_TEXT_FIELD" -> data.optString("target", data.optString("name"))
            "TYPE_TEXT" -> data.optString("text")
            "SCROLL" -> data.optString("direction", "forward")
            "TAP" -> data.optDouble("x").toString()+","+data.optDouble("y").toString()
            "WAIT" -> data.optString("ms")
            "SET_CONTEXT" -> data.optString("key")+"|"+data.optString("value")
            "CHECK_CONTEXT" -> data.optString("key")+"|"+data.optString("expected")+"|"+data.optString("mode","EQUALS")
            "CHECK_APP_INSTALLED","CHECK_NATIVE_CAPABILITY" -> data.optString("name", data.optString("action"))
            else -> if(data.length()==0) "" else data.toString()
        }
    }

    private fun parseDecision(raw:String):RouteDecision {
        val start=raw.indexOf('{'); val end=raw.lastIndexOf('}')
        if(start<0 || end<=start) return RouteDecision("chat","","",raw.trim())
        val j=JSONObject(raw.substring(start,end+1)); val type=j.optString("type","chat").lowercase(); val action=j.optString("action",""); val data=j.optJSONObject("data") ?: JSONObject()
        val payload = payloadFor(action, data)

        val actions=mutableListOf<PlannedAction>(); val arr=j.optJSONArray("actions"); if(arr!=null) for(i in 0 until arr.length()){val a=arr.optJSONObject(i)?:continue;actions.add(PlannedAction(a.optString("action"), payloadFor(a.optString("action"), a.optJSONObject("data") ?: JSONObject())))}
        return RouteDecision(type,action,payload,j.optString("response","").trim(),actions,j.optBoolean("requiresConfirmation",false),j.optString("goal"),j.optString("progress"),j.optString("stateSummary"),j.optString("confirmationPrompt","This action needs confirmation. Say yes to confirm, or say cancel."))
    }
}

/** Application context holder initialized from MainActivity/Application entry. */
object AppContext { @Volatile private var ctx: Context?=null; fun init(context:Context){ctx=context.applicationContext}; fun get():Context=ctx ?: error("Nova application context not initialized") }

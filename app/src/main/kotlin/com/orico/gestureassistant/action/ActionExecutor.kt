package com.orico.gestureassistant.action

import com.orico.gestureassistant.rules.ActionId

data class ActionResult(val successful: Boolean, val message: String)

fun interface ActionExecutor {
    suspend fun execute(action: ActionId, packageName: String?): ActionResult
}

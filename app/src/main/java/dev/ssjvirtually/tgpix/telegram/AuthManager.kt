package dev.ssjvirtually.tgpix.telegram

import org.drinkless.tdlib.TdApi

object AuthManager {
    fun sendPhone(phone: String, callback: (TdApi.Object) -> Unit) {
        val request = TdApi.SetAuthenticationPhoneNumber(phone, null)
        TdlibManager.getClient().send(request) { result ->
            callback(result)
        }
    }

    fun verifyOtp(code: String, callback: (TdApi.Object) -> Unit) {
        val request = TdApi.CheckAuthenticationCode(code)
        TdlibManager.getClient().send(request) { result ->
            callback(result)
        }
    }

    fun logOut(callback: (TdApi.Object) -> Unit) {
        val request = TdApi.LogOut()
        TdlibManager.getClient().send(request) { result ->
            callback(result)
        }
    }
}

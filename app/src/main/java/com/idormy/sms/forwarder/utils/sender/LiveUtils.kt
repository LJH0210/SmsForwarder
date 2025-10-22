package com.idormy.sms.forwarder.utils.sender

import android.content.Context
import android.text.TextUtils
import android.util.Base64
import com.google.gson.Gson
import com.idormy.sms.forwarder.database.entity.Rule
import com.idormy.sms.forwarder.entity.MsgInfo
import com.idormy.sms.forwarder.entity.result.BarkResult
import com.idormy.sms.forwarder.entity.setting.BarkSetting
import com.idormy.sms.forwarder.utils.Log
import com.idormy.sms.forwarder.utils.SendUtils
import com.idormy.sms.forwarder.utils.SettingUtils
import com.idormy.sms.forwarder.utils.interceptor.BasicAuthInterceptor
import com.idormy.sms.forwarder.utils.interceptor.LoggingInterceptor
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.callback.SimpleCallBack
import com.xuexiang.xhttp2.exception.ApiException
import com.xuexiang.xutil.XUtil
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Suppress("RegExpRedundantEscape", "UselessCallOnNotNull")
class LiveUtils {
    companion object {

        private val TAG: String = LiveUtils::class.java.simpleName

        fun sendMsg(
            setting: BarkSetting,
            msgInfo: MsgInfo,
            rule: Rule? = null,
            senderIndex: Int = 0,
            logId: Long = 0L,
            msgId: Long = 0L
        ) {
            //Log.i(TAG, "sendMsg setting:$setting msgInfo:$msgInfo rule:$rule senderIndex:$senderIndex logId:$logId msgId:$msgId")
            val title: String = if (rule != null) {
                msgInfo.getTitleForSend(setting.title, rule.regexReplace)
            } else {
                msgInfo.getTitleForSend(setting.title)
            }
            val content: String = if (rule != null) {
                msgInfo.getContentForSend(rule.smsTemplate, rule.regexReplace)
            } else {
                msgInfo.getContentForSend(SettingUtils.smsTemplate)
            }

            LiveActivityManager.updateWithWakeUp(XUtil.getContext(), "", content)
        }

        fun encrypt(plainText: String, transformation: String, key: String, iv: String): String {
            //Log.d(TAG, "plainText: $plainText, transformation: $transformation, key: $key, iv: $iv")
            val cipher = Cipher.getInstance(transformation)
            val keySpec = SecretKeySpec(key.toByteArray(), "AES")
            if (transformation.contains("ECB")) {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            } else if (transformation.contains("CBC")) {
                val ivSpec = IvParameterSpec(iv.toByteArray())
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            } else {
                throw IllegalArgumentException("Unsupported transformation: $transformation")
            }
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        }

        fun decrypt(encryptedText: String, transformation: String, key: String, iv: String): String {
            //Log.d(TAG, "encryptedText: $encryptedText, transformation: $transformation, key: $key, iv: $iv")
            val cipher = Cipher.getInstance(transformation)
            val keySpec = SecretKeySpec(key.toByteArray(), "AES")
            if (transformation.contains("ECB")) {
                cipher.init(Cipher.DECRYPT_MODE, keySpec)
            } else if (transformation.contains("CBC")) {
                val ivSpec = IvParameterSpec(iv.toByteArray())
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            } else {
                throw IllegalArgumentException("Unsupported transformation: $transformation")
            }
            val encryptedBytes = Base64.decode(encryptedText, Base64.NO_WRAP)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        }

    }
}

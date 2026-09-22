package it.pat.collettori

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.time.Instant

/** Only opaque session tokens, encrypted with an Android Keystore key. Never passwords. */
class SessionStore(context:Context,preferenceName:String="session"){
    private val prefs=context.getSharedPreferences(preferenceName,Context.MODE_PRIVATE)
    private val alias="collettori-session-v1"
    private fun key():SecretKey{
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        if(!ks.containsAlias(alias))KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
        return ks.getKey(alias,null) as SecretKey
    }
    @Synchronized fun save(value:JSONObject){
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val bytes=cipher.doFinal(value.toString().toByteArray())
        check(prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).putString("cipher",Base64.encodeToString(bytes,Base64.NO_WRAP)).putLong("highWater",Instant.now().toEpochMilli()).commit()){"Impossibile salvare la sessione"}
    }
    @Synchronized fun get():JSONObject?{
        val encrypted=prefs.getString("cipher",null)?:return null
        try{
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(prefs.getString("iv",null),Base64.NO_WRAP)))
        return JSONObject(String(cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP))))
        }catch(_:java.security.GeneralSecurityException){return null}
    }
    fun clear(){check(prefs.edit().remove("cipher").remove("iv").commit())}
    val deviceId:String get(){val old=prefs.getString("device",null);if(old!=null)return old;val id=UUID.randomUUID().toString();check(prefs.edit().putString("device",id).commit());return id}
    fun offlineAllowed(s:JSONObject):Boolean{
        val now=Instant.now();val high=prefs.getLong("highWater",0)
        if(now.toEpochMilli()+60_000<high)return false
        prefs.edit().putLong("highWater",maxOf(high,now.toEpochMilli())).apply()
        return now.isBefore(Instant.parse(s.getString("offline_until")))
    }
}

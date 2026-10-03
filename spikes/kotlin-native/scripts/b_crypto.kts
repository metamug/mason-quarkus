import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// boundary: javax.crypto and java.security
fun <T> attempt(f: () -> T): String = try { f().toString() } catch (t: Throwable) { "FAIL " + t.javaClass.simpleName + " " + (t.message ?: "").take(80) }
fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

response["sha256"] = attempt { hex(MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())) }
response["hmac_sha256"] = attempt { val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec("k".toByteArray(), "HmacSHA256")); hex(m.doFinal("abc".toByteArray())).take(16) }
response["aes_gcm_roundtrip"] = attempt {
    val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
    val iv = ByteArray(12) { 1 }
    val enc = Cipher.getInstance("AES/GCM/NoPadding"); enc.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
    val ct = enc.doFinal("secret".toByteArray())
    val dec = Cipher.getInstance("AES/GCM/NoPadding"); dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    String(dec.doFinal(ct))
}
response["pbkdf2"] = attempt { hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("pw".toCharArray(), ByteArray(8), 1000, 128)).encoded).length }
response["secure_random"] = attempt { ByteArray(8).also { SecureRandom().nextBytes(it) }.size }
response["rsa_sign_verify"] = attempt {
    val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val s = Signature.getInstance("SHA256withRSA"); s.initSign(kp.private); s.update("m".toByteArray()); val sig = s.sign()
    val v = Signature.getInstance("SHA256withRSA"); v.initVerify(kp.public); v.update("m".toByteArray()); v.verify(sig)
}

package kz.epharm.learnercredentials

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SealedCredential(val keyId:String,val nonce:ByteArray,val ciphertext:ByteArray) {
    override fun toString()="SealedCredential(REDACTED)"
}

/** Versioned keys are read from an owner-only external file, never from a database or a payload. */
@Component
class LearnerCredentialCrypto(private val config:LearnerCredentialConfig,private val json:ObjectMapper) {
    private val random=SecureRandom()
    fun ready():Boolean=config.enabled && keys()!=null
    fun seal(domain:String,identity:String,fingerprint:String,plaintext:ByteArray):SealedCredential? = runCatching {
        if(!config.enabled) return null
        val ring=keys()?:return null;val nonce=ByteArray(12).also(random::nextBytes)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(ring.second.getValue(ring.first),"AES"),GCMParameterSpec(128,nonce))
        cipher.updateAAD(aad(domain,identity,fingerprint,ring.first))
        SealedCredential(ring.first,nonce,cipher.doFinal(plaintext))
    }.getOrNull()
    fun open(domain:String,identity:String,fingerprint:String,value:SealedCredential):ByteArray? = runCatching {
        if(!config.enabled || value.nonce.size!=12) return null
        val key=keys()?.second?.get(value.keyId)?:return null
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,value.nonce))
        cipher.updateAAD(aad(domain,identity,fingerprint,value.keyId));cipher.doFinal(value.ciphertext)
    }.getOrNull()
    private fun aad(domain:String,identity:String,fingerprint:String,key:String)=
        json.writeValueAsBytes(listOf("epharm-credential-aead-v1",domain,identity,fingerprint,key))
    private fun keys():Pair<String,Map<String,ByteArray>>? = runCatching {
        if(config.keyFile.isBlank()) return null
        val file=Path.of(config.keyFile)
        if(!file.isAbsolute || Files.isSymbolicLink(file) || !Files.isRegularFile(file,NOFOLLOW_LINKS) || file.normalize()!=file.toRealPath() || Files.size(file)>65536) return null
        val permissions=Files.getPosixFilePermissions(file,NOFOLLOW_LINKS)
        if(permissions.any{it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_")}) return null
        if(Files.getPosixFilePermissions(file.parent).any{it in setOf(PosixFilePermission.GROUP_WRITE,PosixFilePermission.OTHERS_WRITE)}) return null
        val root=json.factory.createParser(Files.readAllBytes(file)).use { parser->
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            json.readTree<com.fasterxml.jackson.databind.JsonNode>(parser)
        }
        if(root.fieldNames().asSequence().toSet()!=setOf("version","activeKeyId","keys") || !root.path("version").isIntegralNumber || root.path("version").asInt()!=1 || !root.path("activeKeyId").isTextual || !root.path("keys").isObject) return null
        val active=root.path("activeKeyId").asText();val entries=root.path("keys").fields().asSequence().toList()
        if(entries.size !in 1..16) return null
        val keys=entries.associate{(id,value)->
            if(!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(id) || !value.isTextual) return null
            val bytes=Base64.getDecoder().decode(value.asText());if(bytes.size!=32) return null
            id to bytes
        }
        if(active !in keys) return null
        active to keys
    }.getOrNull()
    companion object {
        fun fingerprint(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
}

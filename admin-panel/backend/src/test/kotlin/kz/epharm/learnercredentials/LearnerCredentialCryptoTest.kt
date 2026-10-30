package kz.epharm.learnercredentials

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

class LearnerCredentialCryptoTest {
    private val json=jacksonObjectMapper()
    private val root=Files.createTempDirectory("synthetic-vault-keys-").toRealPath()
    private val file=root.resolve("keys.json")
    private val first=ByteArray(32).also(SecureRandom()::nextBytes)
    private val second=ByteArray(32).also(SecureRandom()::nextBytes)
    private val config=LearnerCredentialConfig(true,file.toString(),120,25,4)
    private val crypto=LearnerCredentialCrypto(config,json)
    @BeforeEach fun seed(){Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));write("k1",mapOf("k1" to first))}
    private fun write(active:String,keys:Map<String,ByteArray>){Files.write(file,json.writeValueAsBytes(mapOf("version" to 1,"activeKeyId" to active,"keys" to keys.mapValues{Base64.getEncoder().encodeToString(it.value)})));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))}
    @Test fun `AEAD is randomized lossless and bound to identity purpose fingerprint and versioned key`() {
        val value="  =Синтетический тест 42 \t".toByteArray();val a=crypto.seal("learner-password","synthetic-id","current-hash",value)!!
        val b=crypto.seal("learner-password","synthetic-id","current-hash",value)!!
        assertFalse(a.nonce.contentEquals(b.nonce));assertFalse(a.ciphertext.contentEquals(value))
        assertTrue(crypto.open("learner-password","synthetic-id","current-hash",a)!!.contentEquals(value))
        assertNull(crypto.open("learner-password","other-id","current-hash",a));assertNull(crypto.open("learner-export","synthetic-id","current-hash",a));assertNull(crypto.open("learner-password","synthetic-id","old-hash",a))
        val modified=a.ciphertext.clone().also{it[0]=(it[0].toInt() xor 1).toByte()};assertNull(crypto.open("learner-password","synthetic-id","current-hash",SealedCredential(a.keyId,a.nonce,modified)))
    }
    @Test fun `rotation retains historical decryption only while its explicit key exists and restore is sufficient`() {
        val input="synthetic-only".toByteArray();val old=crypto.seal("learner-password","synthetic-id","fp",input)!!
        write("k2",mapOf("k1" to first,"k2" to second));assertTrue(crypto.open("learner-password","synthetic-id","fp",old)!!.contentEquals(input))
        assertEquals("k2",crypto.seal("learner-password","synthetic-id","fp",input)!!.keyId)
        write("k2",mapOf("k2" to second));assertNull(crypto.open("learner-password","synthetic-id","fp",old))
        write("k1",mapOf("k1" to first));val restored=LearnerCredentialCrypto(config,json)
        assertTrue(restored.open("learner-password","synthetic-id","fp",old)!!.contentEquals(input))
    }
    @Test fun `missing malformed unknown key and insecure permissions never produce ciphertext`() {
        Files.delete(file);assertFalse(crypto.ready());assertNull(crypto.seal("a","b","c",byteArrayOf(1)))
        Files.writeString(file,"invalid");Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));assertFalse(crypto.ready())
        Files.writeString(file,"{\"version\":1,\"version\":1,\"activeKeyId\":\"k1\",\"keys\":{}}");assertFalse(crypto.ready())
        write("missing",mapOf("k1" to first));assertFalse(crypto.ready())
        write("k1",mapOf("k1" to ByteArray(31)));assertFalse(crypto.ready())
        write("k1",mapOf("k1" to first));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r-----"));assertFalse(crypto.ready())
    }
    @Test fun `symlink keys and feature disabled cannot enable the vault`() {
        val link=root.resolve("link.json");Files.createSymbolicLink(link,file)
        assertFalse(LearnerCredentialCrypto(LearnerCredentialConfig(true,link.toString(),120,25,4),json).ready())
        assertFalse(LearnerCredentialCrypto(LearnerCredentialConfig(false,file.toString(),120,25,4),json).ready())
    }
    @Test fun `secret-bearing request and envelope string representations do not reveal values`() {
        val secret="synthetic-only-sensitive"
        assertFalse(PrepareCredentialExport(listOf("synthetic-id"),"test",secret).toString().contains(secret))
        assertFalse(DownloadCredentialExport(secret).toString().contains(secret))
        assertFalse(LearnerPasswordCopy(secret,"available").toString().contains(secret))
        assertFalse(SealedCredential("kid",byteArrayOf(1),secret.toByteArray()).toString().contains(secret))
    }
}

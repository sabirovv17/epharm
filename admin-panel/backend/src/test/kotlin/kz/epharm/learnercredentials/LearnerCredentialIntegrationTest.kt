package kz.epharm.learnercredentials

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.*
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.mobile.auth.reset.*
import kz.epharm.mobile.auth.service.*
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.staff.*
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** All identities, credentials and keys are synthetic; no request or secret is printed on failure. */
@SpringBootTest(properties=["app.learner-credentials.enabled=true", "app.posm.staff-auth-enabled=true",
    "app.otp.dev-mode=false", "app.merch-rewards.enabled=false", "app.merch.producer-auth-enabled=false",
    "app.merch.native-executor-enabled=false"])
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class LearnerCredentialIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("credential_test").withUsername("test").withPassword("synthetic-test-only").apply{start()}
        private val keyDir:Path=Files.createTempDirectory("learner-vault-test-").toRealPath().also {
            Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rwx------")) }
        private val keyFile=keyDir.resolve("keys.json")
        @JvmStatic @DynamicPropertySource fun props(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username}
            reg.add("spring.datasource.password"){postgres.password};reg.add("app.learner-credentials.key-file"){keyFile.toString()}
        }
        private const val IIN="951212500015"
        private const val PHONE="+77002223344"
        private const val ADMIN_PASSWORD="Synthetic-HQ-test-2026"
        private const val LEARNER_PASSWORD="  =Exact synthetic 0007  "
    }
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var encoder:PasswordEncoder
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var jwt:JwtService
    @Autowired lateinit var mobile:MobileAuthService
    @Autowired lateinit var reset:MobilePasswordResetService
    @Autowired lateinit var staff:StaffAuthService
    @Autowired lateinit var vault:LearnerPasswordVault
    @Autowired lateinit var crypto:LearnerCredentialCrypto
    @Autowired lateinit var exports:LearnerCredentialExportService
    @Autowired lateinit var txManager:PlatformTransactionManager
    @MockkBean lateinit var provider:OtpProvider
    @MockkBean(relaxed=true) lateinit var scheduledCleanup:CredentialExportCleanup
    private lateinit var admin:AdminUserEntity
    private lateinit var token:String
    private lateinit var keyA:ByteArray
    private lateinit var keyB:ByteArray
    private val device=PosmDeviceIdentity("credential-device","credential-pharmacy",false)
    private val context=StaffContext("synthetic-cashier","synthetic-session","synthetic-workstation","synthetic-process")

    @BeforeEach fun seed() {
        clearMocks(provider)
        every{provider.id} returns "synthetic-external"
        every{provider.verificationMode} returns OtpVerificationMode.EXTERNAL
        every{provider.requestOtp(any(),any())} just Runs
        every{provider.verifyOtp(any(),any())} returns OtpVerificationResult.INVALID
        every{provider.verifyOtp(any(),"1234")} returns OtpVerificationResult.VERIFIED
        // This database belongs exclusively to this test container; no copied/runtime data is used.
        jdbc.execute("TRUNCATE pharmacists,admin_users,unified_persons,chains,mobile_password_reset_rate_limits,posm_staff_rate_limits,mobile_otps CASCADE")
        keyA=ByteArray(32).also(SecureRandom()::nextBytes);keyB=ByteArray(32).also(SecureRandom()::nextBytes)
        ring("a",mapOf("a" to keyA))
        admin=users.saveAndFlush(AdminUserEntity(email="credential-test@example.invalid",name="Synthetic HQ",
            roleRaw=AdminRole.HQ_HEAD.name,passwordHash=encoder.encode(ADMIN_PASSWORD)))
        token=jwt.issueAccessToken(admin)
        val person=UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,?)",person,"synthetic-test-proof")
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)",admin.id,person)
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",admin.id)
        jdbc.update("INSERT INTO chains(id,name,\"group\") VALUES('credential-chain','Synthetic','pilot')")
        for(id in listOf("credential-pharmacy","credential-foreign")) jdbc.update("""INSERT INTO pharmacies(id,name,chain_id,chain_name,city,addr,"group")
            VALUES(?,?,'credential-chain','Synthetic','Synthetic city','Synthetic address','pilot')""",id,id)
        jdbc.update("INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES(?,?,'credential-pharmacy',?,'synthetic')",
            UUID.randomUUID(),device.deviceId,"1".repeat(64))
        learner("credential-person",IIN,PHONE,"credential-pharmacy",LEARNER_PASSWORD)
        learner("credential-foreign-person","000101500011","+77000000012","credential-foreign","Foreign synthetic 2026")
        grant()
    }
    private fun learner(id:String,iin:String,phone:String,pharmacy:String?,password:String?) {
        jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,password_hash,pharmacy_id,pharmacy_name,tier,status)
            VALUES(?,?,?,?,?,?,?,'Silver','active')""",id,"Synthetic learner",iin,phone,password?.let(encoder::encode),pharmacy,pharmacy)
    }
    private fun ring(active:String,keys:Map<String,ByteArray>) {
        Files.write(keyFile,json.writeValueAsBytes(mapOf("version" to 1,"activeKeyId" to active,
            "keys" to keys.mapValues{Base64.getEncoder().encodeToString(it.value)})))
        Files.setPosixFilePermissions(keyFile,PosixFilePermissions.fromString("rw-------"))
    }
    private fun grant(personal:Boolean=true,all:Boolean=false,ids:Set<String> = setOf("credential-pharmacy"),credentials:Boolean=true) {
        val actions=listOf("view")+(if(credentials)listOf("export_credentials") else emptyList())+(if(personal)listOf("personal_data") else emptyList())
        val grants=actions.map{StoredGrant("pharmacists","registry",it,all,if(all)emptySet() else ids)}
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",json.writeValueAsString(grants),admin.id)
    }
    private fun request(path:String,body:Any,auth:String=token):MvcResult= mvc.perform(post(path).header("Authorization","Bearer $auth")
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andReturn()
    private fun prepare(ids:List<String> = listOf("credential-person"),password:String=ADMIN_PASSWORD)=request("/api/admin/learner-credentials/exports",
        mapOf("pharmacistIds" to ids,"purpose" to "Synthetic acceptance","currentPassword" to password))
    private fun prepared():UUID {
        val result=prepare();assertEquals(200,result.response.status)
        return UUID.fromString(node(result)["id"].asText())
    }
    private fun download(id:UUID,password:String=ADMIN_PASSWORD,auth:String=token)=request("/api/admin/learner-credentials/exports/$id/download",mapOf("currentPassword" to password),auth)
    private fun node(result:MvcResult):JsonNode=json.readTree(result.response.contentAsByteArray)
    private fun count(table:String)=jdbc.queryForObject("SELECT count(*) FROM $table",Int::class.java)!!
    private fun currentHash(id:String="credential-person")=jdbc.queryForObject("SELECT password_hash FROM pharmacists WHERE id=?",String::class.java,id)!!
    private fun capture(id:String="credential-person",password:String=LEARNER_PASSWORD) {
        TransactionTemplate(txManager).executeWithoutResult{vault.capture(id,currentHash(id),password)}
    }
    private fun passwordCell(bytes:ByteArray):String=XSSFWorkbook(ByteArrayInputStream(bytes)).use{it.getSheetAt(0).getRow(1).getCell(4).stringCellValue}
    private fun xml(bytes:ByteArray):String=ZipInputStream(ByteArrayInputStream(bytes)).use {zip->buildString {
        while(true){val entry=zip.nextEntry?:break;if(entry.name.endsWith(".xml"))append(zip.readBytes().toString(Charsets.UTF_8))} }}

    @Test fun `preexisting hashes are not guessed and ordinary directory never exposes vault`() {
        val result=download(prepared());assertEquals(200,result.response.status)
        assertTrue(passwordCell(result.response.contentAsByteArray)=="Недоступен: нет зашифрованной копии")
        val directory=mvc.perform(get("/api/admin/pharmacists/credential-person").header("Authorization","Bearer $token")).andReturn()
        assertEquals(200,directory.response.status)
        val fields=node(directory).fieldNames().asSequence().toSet()
        assertTrue(fields.contains("hasPassword")) // The existing availability flag is intentionally public to the permitted roster.
        assertFalse(fields.any{(it.contains("password",true)&&it!="hasPassword")||it.contains("cipher",true)||it.contains("key",true)})
        assertEquals(0,count("learner_password_vault"))
    }
    @Test fun `successful mobile password login captures exact strings and encrypted storage only`() {
        mobile.login(IIN,LEARNER_PASSWORD)
        val result=download(prepared());assertEquals(200,result.response.status)
        assertTrue(result.response.getHeader("Cache-Control")!!.contains("no-store"))
        XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use {book->
            val row=book.getSheetAt(0).getRow(1)
            for(column in listOf(0,3,4,6,7))assertEquals(CellType.STRING,row.getCell(column).cellType)
            assertTrue(row.getCell(4).stringCellValue==LEARNER_PASSWORD)
            assertTrue(row.getCell(3).stringCellValue==IIN)
            assertTrue(row.getCell(7).stringCellValue==IIN)
        }
        val stored=jdbc.queryForMap("SELECT * FROM learner_password_vault")
        assertEquals("captured",stored["state"])
        assertFalse((stored["ciphertext"] as ByteArray).toString(Charsets.UTF_8).contains(LEARNER_PASSWORD))
        assertEquals(2,count("learner_credential_export_attempts"))
    }
    @Test fun `wrong password and SMS or refresh never create a recoverable password`() {
        assertThrows(Exception::class.java){mobile.login(IIN,"Incorrect synthetic value")}
        mobile.requestSms(PHONE);val verified=mobile.verifySms(PHONE,"1234")
        mobile.refresh(verified.tokens!!.refreshToken)
        assertEquals(0,count("learner_password_vault"))
        jdbc.update("UPDATE pharmacists SET password_hash=NULL WHERE id='credential-person'")
        val result=download(prepared());assertEquals(200,result.response.status)
        assertTrue(passwordCell(result.response.contentAsByteArray)=="Вход по SMS: пароль не задан")
    }
    @Test fun `blank legacy login is explicit for password and SMS accounts without altering exact password`() {
        capture();jdbc.update("UPDATE pharmacists SET iin='' WHERE id='credential-person'")
        val passwordResult=download(prepared());assertEquals(200,passwordResult.response.status)
        XSSFWorkbook(ByteArrayInputStream(passwordResult.response.contentAsByteArray)).use {book->
            val row=book.getSheetAt(0).getRow(1)
            assertTrue(row.getCell(3).stringCellValue=="Недоступен: логин не настроен")
            assertTrue(row.getCell(4).stringCellValue==LEARNER_PASSWORD)
        }
        jdbc.update("UPDATE pharmacists SET password_hash=NULL,phone='' WHERE id='credential-person'")
        val smsResult=download(prepared());assertEquals(200,smsResult.response.status)
        XSSFWorkbook(ByteArrayInputStream(smsResult.response.contentAsByteArray)).use {book->
            val row=book.getSheetAt(0).getRow(1)
            assertTrue(row.getCell(3).stringCellValue=="Недоступен: логин не настроен")
            assertTrue(row.getCell(4).stringCellValue=="Вход по SMS: пароль не задан")
        }
    }
    @Test fun `initial password activation and recovery capture the actual effective credential`() {
        jdbc.update("UPDATE pharmacists SET password_hash=NULL WHERE id='credential-person'")
        mobile.requestActivationSms(IIN,PHONE);mobile.verifyActivationSms(IIN,PHONE,"1234")
        mobile.setInitialPassword(IIN,PHONE,LEARNER_PASSWORD)
        assertTrue(passwordCell(download(prepared()).response.contentAsByteArray)==LEARNER_PASSWORD)
        jdbc.execute("TRUNCATE posm_staff_rate_limits")
        val pending=prepared();val newPassword="  +New synthetic 0008  "
        val challenge=reset.request(IIN,"127.0.0.1")
        val proof=reset.verify(VerifyMobilePasswordReset(IIN,challenge.challengeId,"1234"))
        reset.complete(CompleteMobilePasswordReset(IIN,proof.resetToken,newPassword))
        assertEquals(409,download(pending).response.status)
        assertTrue(passwordCell(download(prepared()).response.contentAsByteArray)==newPassword)
    }
    @Test fun `staff password login reset activation and registration capture without enabling production RPS`() {
        staff.login(device,StaffLoginRequest(IIN,LEARNER_PASSWORD,context))
        assertTrue(passwordCell(download(prepared()).response.contentAsByteArray)==LEARNER_PASSWORD)
        val next="Staff synthetic 0009"
        val challenge=staff.requestOtp(device,StaffOtpRequest("reset",IIN,PHONE))
        staff.complete(device,StaffPasswordRequest(challenge.challengeId,"1234",IIN,PHONE,next),"reset")
        assertTrue(passwordCell(download(prepared()).response.contentAsByteArray)==next)
        jdbc.update("UPDATE pharmacists SET password_hash=NULL WHERE id='credential-person'")
        jdbc.execute("TRUNCATE posm_staff_rate_limits")
        val activation=staff.requestOtp(device,StaffOtpRequest("activate",IIN,PHONE))
        staff.complete(device,StaffPasswordRequest(activation.challengeId,"1234",IIN,PHONE,LEARNER_PASSWORD),"activate")
        assertTrue(TransactionTemplate(txManager).execute{vault.read("credential-person",currentHash()).password==LEARNER_PASSWORD}==true)
        val newIin="880404400014";val newPhone="+77000000014"
        val registration=staff.requestOtp(device,StaffOtpRequest("register",newIin,newPhone))
        staff.complete(device,StaffPasswordRequest(registration.challengeId,"1234",newIin,newPhone,next,"Synthetic new learner"),"register")
        val id=jdbc.queryForObject("SELECT id FROM pharmacists WHERE iin=?",String::class.java,newIin)!!
        assertTrue(TransactionTemplate(txManager).execute{vault.read(id,currentHash(id)).password==next}==true)
        assertEquals("pending",jdbc.queryForObject("SELECT status FROM pharmacists WHERE id=?",String::class.java,id))
    }
    @Test fun `hash change without a new copy never labels the old password as current`() {
        capture();val oldHash=currentHash()
        jdbc.update("UPDATE pharmacists SET password_hash=? WHERE id='credential-person'",encoder.encode("Changed synthetic 2026"))
        // Prove stale captures are rejected explicitly, rather than relying on an assumed caller.
        TransactionTemplate(txManager).executeWithoutResult{vault.capture("credential-person",oldHash,LEARNER_PASSWORD)}
        val result=download(prepared());assertEquals(200,result.response.status)
        assertTrue(passwordCell(result.response.contentAsByteArray)=="Недоступен: копия не соответствует текущему паролю")
    }
    @Test fun `legacy HQ learner create and password update capture without copying the administrator password`() {
        jdbc.update("UPDATE admin_users SET access_mode='LEGACY' WHERE id=?",admin.id)
        val created=request("/api/admin/pharmacists",mapOf("name" to "Synthetic new account","iin" to "880404400014",
            "phone" to "+77000000014","password" to LEARNER_PASSWORD,"pharmacyId" to "credential-pharmacy"))
        assertEquals(200,created.response.status)
        val id=node(created)["id"].asText()
        assertTrue(TransactionTemplate(txManager).execute{vault.read(id,currentHash(id)).password==LEARNER_PASSWORD}==true)
        val changed="  @Changed synthetic 0010  "
        val updated=mvc.perform(patch("/api/admin/pharmacists/$id").header("Authorization","Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(mapOf("password" to changed)))).andReturn()
        assertEquals(200,updated.response.status)
        assertTrue(TransactionTemplate(txManager).execute{vault.read(id,currentHash(id)).password==changed}==true)
        assertEquals(1,count("learner_password_vault"))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM learner_password_vault WHERE pharmacist_id=?",Int::class.java,admin.id.toString()))
    }
    @Test fun `key unavailable does not break login and cannot return an older captured credential`() {
        capture();Files.delete(keyFile)
        assertNotNull(mobile.login(IIN,LEARNER_PASSWORD).tokens)
        assertEquals("unavailable",jdbc.queryForObject("SELECT state FROM learner_password_vault",String::class.java))
        assertEquals(503,prepare().response.status)
        ring("a",mapOf("a" to keyA));mobile.login(IIN,LEARNER_PASSWORD)
        assertTrue(passwordCell(download(prepared()).response.contentAsByteArray)==LEARNER_PASSWORD)
    }
    @Test fun `optional vault database capture failure rolls back its savepoint not successful login`() {
        jdbc.execute("ALTER TABLE learner_password_vault RENAME TO unavailable_vault_test")
        try{assertNotNull(mobile.login(IIN,LEARNER_PASSWORD).tokens)}finally{jdbc.execute("ALTER TABLE unavailable_vault_test RENAME TO learner_password_vault")}
        assertEquals(0,count("learner_password_vault"));assertTrue(count("mobile_refresh_tokens")>0)
        assertEquals(200,download(prepared()).response.status)
    }
    @Test fun `role directory export and signed legacy HQ alone cannot decrypt passwords`() {
        grant(credentials=false);assertEquals(403,prepare().response.status)
        jdbc.update("UPDATE admin_users SET access_mode='LEGACY' WHERE id=?",admin.id)
        assertEquals(403,prepare().response.status)
        grant();assertEquals(200,prepare().response.status) // Explicit verified legacy bootstrap profile remains usable.
        for(role in listOf("CATEGORY_LEAD","REGIONAL_MANAGER","TRAINING_MANAGER")) {
            jdbc.update("UPDATE admin_users SET role=? WHERE id=?",role,admin.id)
            assertEquals(403,prepare().response.status)
        }
    }
    @Test fun `empty duplicate oversized and foreign batches never produce a partial file`() {
        assertEquals(400,prepare(emptyList()).response.status)
        assertEquals(400,prepare(listOf("credential-person","credential-person")).response.status)
        assertEquals(400,prepare((1..26).map{"synthetic-$it"}).response.status)
        assertEquals(403,prepare(listOf("credential-person","credential-foreign-person")).response.status)
        assertEquals(0,count("learner_credential_exports"))
    }
    @Test fun `PII cannot escape through a login cell or any workbook XML without separate personal grant`() {
        capture();grant(personal=false)
        val result=download(prepared());assertEquals(200,result.response.status)
        val contents=xml(result.response.contentAsByteArray)
        assertFalse(contents.contains(IIN));assertFalse(contents.contains(PHONE))
        assertTrue(passwordCell(result.response.contentAsByteArray)==LEARNER_PASSWORD)
        assertTrue(contents.contains("Нет права",true)||contents.contains("нет права",true))
    }
    @Test fun `failed fresh password proofs consume durable bounded actor budget`() {
        repeat(4){assertEquals(403,prepare(password="Wrong synthetic proof").response.status)}
        assertEquals(429,prepare().response.status)
        assertEquals(4,count("learner_credential_export_attempts"));assertEquals(0,count("learner_credential_exports"))
        assertEquals(4,count("learner_credential_export_audit"))
    }
    @Test fun `download is actor bound requires fresh password and is single use`() {
        capture();val id=prepared()
        val other=users.saveAndFlush(AdminUserEntity(email="foreign-test@example.invalid",name="Synthetic foreign HQ",roleRaw="HQ_HEAD",passwordHash=encoder.encode(ADMIN_PASSWORD)))
        assertEquals(403,download(id,auth=jwt.issueAccessToken(other)).response.status)
        assertEquals(403,download(id,password="Wrong synthetic proof").response.status)
        assertEquals(200,download(id).response.status)
        assertEquals(404,download(id).response.status)
        assertEquals(0,count("learner_credential_exports"))
    }
    @Test fun `admin password grant version and learner status changes invalidate prepared data`() {
        capture();val first=prepared()
        jdbc.update("UPDATE admin_users SET password_hash=? WHERE id=?",encoder.encode("Rotated synthetic HQ"),admin.id)
        assertEquals(403,download(first).response.status)
        assertEquals(409,download(first,"Rotated synthetic HQ").response.status)
        jdbc.update("UPDATE admin_users SET password_hash=? WHERE id=?",encoder.encode(ADMIN_PASSWORD),admin.id)
        val second=prepared();grant(credentials=false)
        assertEquals(403,download(second).response.status)
        grant();assertEquals(409,download(second).response.status)
        val third=prepared();jdbc.update("UPDATE pharmacists SET status='blocked' WHERE id='credential-person'")
        assertEquals(409,download(third).response.status)
        assertEquals(0,count("learner_credential_exports"))
    }
    @Test fun `expired revoked and idle jobs lose ciphertext while audit metadata is retained`() {
        capture();val expired=prepared()
        jdbc.update("UPDATE learner_credential_exports SET created_at=now()-interval '5 minutes',expires_at=now()-interval '1 second' WHERE id=?",expired)
        assertEquals(404,download(expired).response.status)
        val revoked=prepared()
        assertEquals(204,mvc.perform(delete("/api/admin/learner-credentials/exports/$revoked").header("Authorization","Bearer $token")).andReturn().response.status)
        assertEquals(404,download(revoked).response.status)
        prepared();jdbc.update("UPDATE learner_credential_exports SET created_at=now()-interval '5 minutes',expires_at=now()-interval '1 second'")
        assertEquals(1,exports.cleanup());assertEquals(0,count("learner_credential_exports"))
        assertTrue(count("learner_credential_export_audit")>=6)
    }
    @Test fun `encrypted export and vault keys rotate restore and detect tampered identity ciphertext`() {
        capture();val id=prepared();val restored=prepared()
        ring("b",mapOf("a" to keyA,"b" to keyB))
        assertTrue(passwordCell(download(id).response.contentAsByteArray)==LEARNER_PASSWORD)
        ring("b",mapOf("b" to keyB))
        assertEquals(503,download(restored).response.status)
        ring("b",mapOf("a" to keyA,"b" to keyB))
        assertEquals(200,download(restored).response.status)
        jdbc.update("UPDATE learner_password_vault SET ciphertext=set_byte(ciphertext,0,get_byte(ciphertext,0)#1)")
        assertEquals(503,prepare().response.status)
        assertEquals(0,count("learner_credential_exports"))
    }
    @Test fun `ciphertext substituted between export jobs cannot be decrypted`() {
        capture();val a=prepared();val b=prepared()
        jdbc.update("UPDATE learner_credential_exports SET ciphertext=(SELECT ciphertext FROM learner_credential_exports WHERE id=?) WHERE id=?",a,b)
        assertEquals(503,download(b).response.status)
        assertEquals(200,download(a).response.status)
    }
    @Test fun `parallel downloads return one file and never reuse plaintext`() {
        capture();val id=prepared();val pool=Executors.newFixedThreadPool(2)
        try {
            val start=CountDownLatch(1)
            val tasks=(1..2).map{pool.submit<Int>{start.await();download(id).response.status}}
            start.countDown();assertEquals(listOf(200,404),tasks.map{it.get(20,TimeUnit.SECONDS)}.sorted())
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM learner_credential_export_audit WHERE operation='downloaded'",Int::class.java))
        }finally{pool.shutdownNow()}
    }
    @Test fun `inflight credential change serializes before download and rejects an old snapshot`() {
        capture();val id=prepared();val pool=Executors.newFixedThreadPool(2)
        val changed=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val writer=pool.submit{TransactionTemplate(txManager).executeWithoutResult {
                jdbc.queryForMap("SELECT id FROM pharmacists WHERE id='credential-person' FOR UPDATE")
                val hash=encoder.encode("Concurrent synthetic replacement")
                jdbc.update("UPDATE pharmacists SET password_hash=? WHERE id='credential-person'",hash)
                vault.capture("credential-person",hash,"Concurrent synthetic replacement")
                changed.countDown();check(release.await(10,TimeUnit.SECONDS))
            }}
            assertTrue(changed.await(10,TimeUnit.SECONDS))
            val reader=pool.submit<Int>{download(id).response.status}
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
            var waiting=false
            while(System.nanoTime()<end&&!waiting) {
                waiting=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FOR UPDATE OF p%')",Boolean::class.java)==true
                if(!waiting)Thread.sleep(10)
            }
            assertTrue(waiting,"Download must actually wait for the credential row lock")
            release.countDown();writer.get(15,TimeUnit.SECONDS);assertEquals(409,reader.get(15,TimeUnit.SECONDS))
            assertEquals(0,count("learner_credential_exports"))
        }finally{release.countDown();pool.shutdownNow()}
    }
    @Test fun `audit and external key directory never contain exported plaintext or login data`() {
        capture();download(prepared())
        val audit=json.writeValueAsString(jdbc.queryForList("SELECT * FROM learner_credential_export_audit"))
        assertFalse(audit.contains(LEARNER_PASSWORD));assertFalse(audit.contains(IIN));assertFalse(audit.contains(PHONE))
        Files.list(keyDir).use{assertEquals(listOf("keys.json"),it.map{p->p.fileName.toString()}.sorted().toList())}
        assertEquals(0,count("learner_credential_exports"))
    }
}

package app.absentmotion.library

import app.absentmotion.export.writeProjectPackage
import app.absentmotion.model.Scene
import app.absentmotion.model.mediaUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DeviceLinkTest {
    @get:Rule val tmp = TemporaryFolder()

    private val loopback = { listOf("127.0.0.1") }
    private val phone = Peer("phone-id", "Phone", computer = false, sends = true)
    private val computer = Peer("computer-id", "Computer", computer = true, sends = false)

    private fun server(sends: Boolean = false, now: () -> Long = System::currentTimeMillis) = LinkServer("Computer", true, sends, null, loopback, now, InetAddress.getLoopbackAddress())

    private fun problemOf(block: suspend () -> Unit): LinkProblem? = try {
        runBlocking { withTimeout(20_000) { block() } }
        null
    } catch (e: LinkFailure) {
        e.problem
    }

    private fun pair(server: LinkServer, code: LinkCode = server.issue()): Pair<LinkChannel, LinkChannel> = runBlocking {
        val accepted = async(Dispatchers.IO) { server.accept() }
        val client = withTimeout(20_000) { linkConnect(code) }
        client to accepted.await()
    }

    @Test fun codeTextRoundTripsAndRejectsOtherQrCodes() {
        val code = LinkCode(listOf("192.168.1.20", "10.0.0.5"), 49152, "Jo's Mac & co", ByteArray(32) { it.toByte() }, 1_800_000_000_000, sends = true, computer = true, wifi = "Home 5G")
        val read = LinkCode.parse(code.text())!!
        assertEquals(code.hosts, read.hosts)
        assertEquals(49152, read.port)
        assertEquals("Jo's Mac & co", read.name)
        assertArrayEquals(code.key, read.key)
        assertEquals(code.expires, read.expires)
        assertTrue(read.sends && read.computer)
        assertEquals("Home 5G", read.wifi)
        assertNull(LinkCode.parse("https://alight.link/abc"))
        assertNull(LinkCode.parse(code.text().replace("&k=", "&k=AA")))
        assertNull(privateHost("8.8.8.8"))
        assertNull(privateHost("example.com"))
        assertNotNull(privateHost("192.168.1.20"))
    }

    @Test fun rightKeyConnectsAndFramesAreEncrypted() {
        server().use { server ->
            val (client, host) = pair(server)
            runBlocking {
                val greeted = async(Dispatchers.IO) { greet(host, computer, client = false) }
                assertEquals("Computer", greet(client, phone, client = true).name)
                assertEquals("phone-id", greeted.await().id)
            }
            client.send(LinkChannel.Data, "hello".toByteArray())
            assertEquals("hello", host.receive().text)
            client.close()
            host.close()
        }
    }

    @Test fun wrongKeyIsRefused() {
        server().use { server ->
            val code = server.issue()
            thread(isDaemon = true) { runCatching { server.accept() } }
            val forged = LinkCode(code.hosts, code.port, code.name, ByteArray(32) { 7 }, code.expires, code.sends, code.computer)
            assertEquals(LinkProblem.Expired, problemOf { linkConnect(forged) })
        }
    }

    @Test fun expiredAndUsedCodesAreRefused() {
        var clock = 1_000_000L
        server(now = { clock }).use { server ->
            val code = server.issue()
            thread(isDaemon = true) { runCatching { server.accept() } }
            clock += CodeLifeMs + 1
            assertEquals(LinkProblem.Expired, problemOf { linkConnect(code) })
        }
        server().use { server ->
            val code = server.issue()
            val (client, host) = pair(server, code)
            thread(isDaemon = true) { runCatching { server.accept() } }
            assertEquals(LinkProblem.Expired, problemOf { linkConnect(code) })
            client.close()
            host.close()
        }
    }

    @Test fun bothSendingIsRefused() {
        server(sends = true).use { server ->
            val (client, host) = pair(server)
            val serverSide = thread(isDaemon = true) { runCatching { runBlocking { greet(host, computer.copySends(true), client = false) } } }
            assertEquals(LinkProblem.SameMode, problemOf { greet(client, phone, client = true) })
            serverSide.join(5_000)
        }
    }

    @Test fun nothingIsReachableWhenTheCodeListsNoPrivateAddress() {
        val code = LinkCode(listOf("8.8.8.8"), 1, "x", ByteArray(32), Long.MAX_VALUE, sends = false, computer = true)
        assertEquals(LinkProblem.Unreachable, problemOf { linkConnect(code) })
    }

    @Test fun cancellingJustAsTheConnectionOpensClosesTheSocket() {
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { listener ->
            val code = LinkCode(listOf("127.0.0.1"), listener.localPort, "x", ByteArray(32), Long.MAX_VALUE, sends = false, computer = true)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val busy = CountDownLatch(1)
                pool.execute { busy.await() }
                val job = CoroutineScope(pool.asCoroutineDispatcher()).launch(start = CoroutineStart.UNDISPATCHED) { linkConnect(code) }
                listener.accept().use { accepted ->
                    Thread.sleep(300)
                    job.cancel()
                    busy.countDown()
                    runBlocking { withTimeout(5_000) { job.join() } }
                    accepted.soTimeout = 3_000
                    assertEquals(-1, accepted.getInputStream().read())
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test fun receiverCancelStopsTheSenderAndLeavesNoPartialFile() {
        val payload = tmp.newFile("big.absproj").apply { writeBytes(ByteArray(8 shl 20) { (it * 31).toByte() }) }
        val inbox = tmp.newFolder("inbox")
        server().use { server ->
            val (client, host) = pair(server)
            val receiver = thread(isDaemon = true) {
                runCatching {
                    runBlocking { receiveProject(host, inbox, { Arrival(Arrival.New) }, { if (it > 0) host.cancel() }) { _, _, _ -> "never" } }
                }
            }
            assertEquals(LinkProblem.Cancelled, problemOf { sendProject(client, Offer("Big", payload.length(), 0, "p1"), payload, {}, {}) })
            receiver.join(10_000)
            assertTrue(inbox.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun senderCancelStopsTheReceiverAndLeavesNoPartialFile() {
        val payload = tmp.newFile("big.absproj").apply { writeBytes(ByteArray(8 shl 20) { (it * 7).toByte() }) }
        val inbox = tmp.newFolder("inbox")
        server().use { server ->
            val (client, host) = pair(server)
            thread(isDaemon = true) { runCatching { runBlocking { sendProject(client, Offer("Big", payload.length(), 0, "p1"), payload, {}, { if (it > 0) client.cancel() }) } } }
            assertEquals(LinkProblem.Cancelled, problemOf { receiveProject(host, inbox, { Arrival(Arrival.New) }, {}) { _, _, _ -> "never" } })
            assertTrue(inbox.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun aCorruptedChunkIsCaughtAndThrownAway() {
        val payload = tmp.newFile("p.absproj").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val inbox = tmp.newFolder("inbox")
        server().use { server ->
            val code = server.issue()
            val proxy = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            thread(isDaemon = true) {
                val inner = proxy.accept()
                val outer = Socket("127.0.0.1", code.port)
                thread(isDaemon = true) { runCatching { outer.getInputStream().copyTo(inner.getOutputStream()) } }
                runCatching {
                    val input = inner.getInputStream()
                    val output = outer.getOutputStream()
                    val buffer = ByteArray(4096)
                    var seen = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (seen < 150_000 && seen + n >= 150_000) buffer[(150_000 - seen).toInt()] = (buffer[(150_000 - seen).toInt()].toInt() xor 0x40).toByte()
                        seen += n
                        output.write(buffer, 0, n)
                    }
                }
            }
            val (client, host) = pair(server, LinkCode(code.hosts, proxy.localPort, code.name, code.key, code.expires, code.sends, code.computer))
            val sender = background { sendProject(client, Offer("P", payload.length(), 0, "p1"), payload, {}, {}) }
            assertEquals(LinkProblem.Damaged, problemOf { receiveProject(host, inbox, { Arrival(Arrival.New) }, {}) { _, _, _ -> "never" } })
            assertTrue(sender.get(20, TimeUnit.SECONDS).isFailure)
            assertTrue(inbox.listFiles().orEmpty().isEmpty())
            proxy.close()
        }
    }

    private fun Peer.copySends(sends: Boolean) = Peer(id, name, computer, sends)

    private fun <T> background(block: suspend () -> T): CompletableFuture<Result<T>> = CompletableFuture.supplyAsync { runCatching { runBlocking { block() } } }

    private fun project(root: File, title: String, picture: ByteArray): Pair<ProjectStore, String> {
        val store = ProjectStore(root)
        val id = store.newId()
        val media = File(store.mediaDir(id).apply { mkdirs() }, "pic.png").apply { writeBytes(picture) }
        val uri = media.toURI().toString()
        store.save(id, """<scene title="$title" width="320" height="320" fps="30" totalTime="1000" bgcolor="#ff000000"><media uri="$uri" type="image/png" filename="pic.png" size="${picture.size}"/><shape id="1" label="Pic" startTime="0" endTime="1000" fillType="media" fillImage="$uri" s=".rect"/></scene>""")
        return store to id
    }

    private fun pack(store: ProjectStore, root: File, id: String): File {
        val scene = store.load(id)!!
        return tmp.newFile("send-$id.absproj").also { file ->
            file.outputStream().use { out -> runBlocking { writeProjectPackage(scene, scene.toXml(), out, MediaAccess.forPhone(root), { File(URI(it)).inputStream() }) {} } }
        }
    }

    private fun transfer(from: Peer, to: Peer, fromStore: ProjectStore, fromRoot: File, fromRecords: SentProjects, id: String, toStore: ProjectStore, toRoot: File, toRecords: SentProjects): Pair<String, Arrival> {
        val file = pack(fromStore, fromRoot, id)
        val offer = Offer(fromStore.load(id)!!.title, file.length(), 1, id, fromRecords[id]?.takeIf { it.peer == to.id }?.peerProject)
        val hash = contentHash(fromStore.text(id)!!)
        server().use { server ->
            val (client, host) = pair(server)
            var plan: Arrival? = null
            val received = background {
                receiveProject(host, File(toRoot, "inbox"), { arrivalPlan(toStore, toRecords, it, from) { title -> "$title (from ${if (from.computer) "computer" else "phone"})" } }, {}) { part, o, a ->
                    val report = Importer(toStore, access = MediaAccess.forPhone(toRoot)).import { part.inputStream() }.single()
                    arrive(toStore, toRecords, report.id, a, from, o)
                }
            }
            val theirs = runBlocking { withTimeout(20_000) { sendProject(client, offer, file, { plan = it }, {}) } }
            val landed = received.get(20, TimeUnit.SECONDS).getOrThrow()
            assertEquals(landed, theirs)
            fromRecords[id] = SentRecord(to.id, theirs, hash)
            client.close()
            host.close()
            return theirs to plan!!
        }
    }

    @Test fun aRealPackageGoesThereAndComesBackReplacingTheUntouchedOriginal() {
        val picture = ByteArray(5000) { (it * 13).toByte() }
        val phoneRoot = tmp.newFolder("phone")
        val computerRoot = tmp.newFolder("computer")
        val (phoneStore, original) = project(phoneRoot, "Styles", picture)
        val computerStore = ProjectStore(computerRoot)
        val phoneRecords = SentProjects(File(phoneRoot, "sent.json"))
        val computerRecords = SentProjects(File(computerRoot, "sent.json"))

        val (copy, firstPlan) = transfer(phone, computer, phoneStore, phoneRoot, phoneRecords, original, computerStore, computerRoot, computerRecords)
        assertEquals(Arrival.New, firstPlan.plan)
        val arrived = computerStore.load(copy)!!
        assertEquals("Styles", arrived.title)
        val arrivedMedia = File(URI(arrived.node.mediaUris().single()))
        assertTrue(arrivedMedia.path.startsWith(computerRoot.path))
        assertArrayEquals(picture, arrivedMedia.readBytes())
        assertTrue(File(computerRoot, "inbox").listFiles().orEmpty().isEmpty())

        computerStore.save(copy, computerStore.load(copy)!!.withTitle("Styles edited"))
        val (back, backPlan) = transfer(computer, phone, computerStore, computerRoot, computerRecords, copy, phoneStore, phoneRoot, phoneRecords)
        assertEquals(Arrival.Replace, backPlan.plan)
        assertEquals(original, back)
        assertEquals("Styles edited", phoneStore.load(original)!!.title)
        assertEquals(1, phoneStore.list().size)
        assertEquals(1, phoneStore.versions.list(original).size)
        val backMedia = File(URI(phoneStore.load(original)!!.node.mediaUris().single()))
        assertTrue(backMedia.path.startsWith(phoneStore.mediaDir(original).path))
        assertArrayEquals(picture, backMedia.readBytes())
    }

    @Test fun whenBothCopiesChangedTheArrivalIsKeptNextToTheOriginal() {
        val phoneRoot = tmp.newFolder("phone")
        val computerRoot = tmp.newFolder("computer")
        val (phoneStore, original) = project(phoneRoot, "Styles", ByteArray(100) { 1 })
        val computerStore = ProjectStore(computerRoot)
        val phoneRecords = SentProjects(File(phoneRoot, "sent.json"))
        val computerRecords = SentProjects(File(computerRoot, "sent.json"))
        val (copy, _) = transfer(phone, computer, phoneStore, phoneRoot, phoneRecords, original, computerStore, computerRoot, computerRecords)

        phoneStore.save(original, phoneStore.load(original)!!.withTitle("Styles phone"))
        computerStore.save(copy, computerStore.load(copy)!!.withTitle("Styles"))
        val (kept, plan) = transfer(computer, phone, computerStore, computerRoot, computerRecords, copy, phoneStore, phoneRoot, phoneRecords)
        assertEquals(Arrival.Both, plan.plan)
        assertEquals("Styles (from computer)", plan.name)
        assertFalse(kept == original)
        assertEquals("Styles phone", phoneStore.load(original)!!.title)
        assertEquals("Styles (from computer)", phoneStore.load(kept)!!.title)
        assertEquals(2, phoneStore.list().size)
    }

    @Test fun anOfferFromAnotherDeviceOrForAnUnknownProjectIsNew() {
        val root = tmp.newFolder("phone")
        val (store, id) = project(root, "Styles", ByteArray(10))
        val records = SentProjects(File(root, "sent.json"))
        records[id] = SentRecord("computer-id", "c1", contentHash(store.text(id)!!))
        val stranger = Peer("other-id", "Other", computer = true, sends = true)
        assertEquals(Arrival.New, arrivalPlan(store, records, Offer("Styles", 1, 0, "c1", id), stranger) { it }.plan)
        assertEquals(Arrival.New, arrivalPlan(store, records, Offer("Styles", 1, 0, "c2", id), computer) { it }.plan)
        assertEquals(Arrival.New, arrivalPlan(store, records, Offer("Styles", 1, 0, "c1", "../evil"), computer) { it }.plan)
        assertEquals(Arrival.Replace, arrivalPlan(store, records, Offer("Styles", 1, 0, "c1", id), computer) { it }.plan)
    }
}

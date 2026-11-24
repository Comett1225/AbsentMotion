package app.absentmotion.library

import app.absentmotion.model.Scene
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val LinkVersion = 1
internal const val CodeLifeMs = 5 * 60_000L
internal const val CodeRefreshMs = 270_000L
private const val Scheme = "absentmotion-send:"
private val Magic = "AMS$LinkVersion".toByteArray()
private const val ChunkBytes = 64 * 1024
private const val MaxFrame = 1 shl 20
private const val HandshakeMs = 10_000
private const val ReplyMs = 120_000
private const val DataMs = 30_000
private const val ImportMs = 600_000
private const val StallNs = 30_000_000_000L

internal enum class LinkProblem { Unreachable, Expired, Version, SameMode, Cancelled, Lost, Damaged, Failed }

internal class LinkFailure(val problem: LinkProblem, val detail: String? = null) : IOException(problem.name + (detail?.let { ": $it" } ?: ""))

internal class LinkCode(
    val hosts: List<String>, val port: Int, val name: String, val key: ByteArray, val expires: Long,
    val sends: Boolean, val computer: Boolean, val wifi: String? = null, val version: Int = LinkVersion,
) {
    fun text(): String = buildString {
        append(Scheme).append(version).append("?h=").append(hosts.joinToString(",")).append("&p=").append(port)
        append("&n=").append(enc(name)).append("&k=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(key))
        append("&x=").append(expires / 1000).append("&m=").append(if (sends) 's' else 'r').append("&d=").append(if (computer) 'c' else 'p')
        if (wifi != null) append("&w=").append(enc(wifi))
    }

    companion object {
        private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

        fun parse(text: String): LinkCode? {
            if (!text.startsWith(Scheme)) return null
            return runCatching {
                val version = text.substring(Scheme.length).substringBefore('?').toInt()
                val q = text.substringAfter('?', "").split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
                LinkCode(
                    q.getValue("h").split(',').filter { it.isNotBlank() }.take(8), q.getValue("p").toInt().also { require(it in 1..65535) },
                    q["n"].orEmpty().take(64), Base64.getUrlDecoder().decode(q.getValue("k")).also { require(it.size == 32) },
                    q.getValue("x").toLong() * 1000, q["m"] == "s", q["d"] == "c", q["w"]?.take(64), version,
                )
            }.getOrNull()
        }
    }
}

private val random = SecureRandom()

private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach(::update); doFinal() }

internal fun isPrivate(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
    val bytes = address.address
    return address is Inet6Address && (bytes[0].toInt() and 0xFE) == 0xFC
}

private val Ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
private val Ipv6 = Regex("""[0-9A-Fa-f:]+(%[\w.]+)?""")

internal fun privateHost(host: String): InetAddress? =
    if (!Ipv4.matches(host) && !(host.contains(':') && Ipv6.matches(host))) null
    else runCatching { InetAddress.getByName(host) }.getOrNull()?.takeIf(::isPrivate)

private val SkippedInterfaces = listOf("rmnet", "ccmni", "pdp", "dummy", "docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "utun", "tun", "tap", "wg", "zt", "tailscale", "awdl", "llw", "bridge", "ipsec")
private val SkippedNames = listOf("virtual", "vmware", "hyper-v", "virtualbox", "tailscale", "zerotier", "wireguard", "parallels", "vpn")

internal fun lanHosts(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { n ->
        runCatching { n.isUp && !n.isLoopback && !n.isVirtual && !n.isPointToPoint }.getOrDefault(false) &&
            SkippedInterfaces.none { n.name.lowercase().startsWith(it) } && SkippedNames.none { n.displayName.orEmpty().lowercase().contains(it) }
    }.sortedBy { n -> if (n.name.startsWith("wl") || n.name == "en0" || n.displayName.orEmpty().contains("Wi-Fi") || n.displayName.orEmpty().contains("Wireless")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .filter { it is Inet4Address && it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }.distinct().take(4)
}.getOrDefault(emptyList())

internal class Frame(val type: Int, val body: ByteArray) {
    val text get() = body.toString(Charsets.UTF_8)
    fun json(): JSONObject = try { JSONObject(text) } catch (e: org.json.JSONException) { throw LinkFailure(LinkProblem.Damaged) }
}

internal class LinkChannel(private val socket: Socket, private val sendKey: ByteArray, private val receiveKey: ByteArray) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), ChunkBytes + 64))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), ChunkBytes + 64))
    private val writing = ReentrantLock()
    private var sent = 0L
    private var received = 0L

    var timeoutMs: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }

    private fun cipher(mode: Int, key: ByteArray, counter: Long) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(counter).array()))
    }

    fun send(type: Int, body: ByteArray = ByteArray(0), offset: Int = 0, length: Int = body.size) {
        writing.lock()
        try { write(type, body, offset, length) } finally { writing.unlock() }
    }

    fun send(type: Int, json: JSONObject) = send(type, json.toString().toByteArray())

    private fun write(type: Int, body: ByteArray, offset: Int, length: Int) {
        val c = cipher(Cipher.ENCRYPT_MODE, sendKey, sent++)
        c.updateAAD(byteArrayOf(type.toByte()))
        val sealed = c.doFinal(body, offset, length)
        output.writeInt(sealed.size + 1)
        output.writeByte(type)
        output.write(sealed)
        output.flush()
    }

    fun receive(): Frame {
        val size = input.readInt()
        if (size !in 17..MaxFrame) throw LinkFailure(LinkProblem.Damaged)
        val type = input.readUnsignedByte()
        val sealed = ByteArray(size - 1).also(input::readFully)
        val body = try {
            cipher(Cipher.DECRYPT_MODE, receiveKey, received++).run { updateAAD(byteArrayOf(type.toByte())); doFinal(sealed) }
        } catch (e: GeneralSecurityException) {
            throw LinkFailure(LinkProblem.Damaged)
        }
        return Frame(type, body)
    }

    fun expect(type: Int): Frame {
        val frame = receive()
        return when (frame.type) {
            type -> frame
            Cancel -> throw LinkFailure(LinkProblem.Cancelled)
            Fail -> frame.json().let { throw LinkFailure(runCatching { LinkProblem.valueOf(it.optString("problem")) }.getOrDefault(LinkProblem.Failed), it.optString("detail").ifBlank { null }) }
            else -> throw LinkFailure(LinkProblem.Damaged)
        }
    }

    fun fail(problem: LinkProblem, detail: String? = null) = runCatching { send(Fail, JSONObject().put("problem", problem.name).put("detail", detail.orEmpty())) }

    @Volatile private var cancelled = false

    fun cancel() {
        if (cancelled) return
        cancelled = true
        try {
            if (!writing.tryLock(500, TimeUnit.MILLISECONDS)) return close()
            try {
                write(Cancel, ByteArray(0), 0, 0)
                socket.shutdownOutput()
            } finally {
                writing.unlock()
            }
        } catch (_: Exception) {
            return close()
        }
        Thread { runCatching { Thread.sleep(3_000) }; close() }.apply { isDaemon = true }.start()
    }

    override fun close() { runCatching { socket.close() } }

    companion object {
        const val Hello = 1
        const val Offer = 2
        const val Plan = 3
        const val Data = 4
        const val End = 5
        const val Done = 6
        const val Cancel = 7
        const val Fail = 8
    }
}

private fun keys(key: ByteArray, client: ByteArray, server: ByteArray) =
    hmac(key, "c2s".toByteArray(), client, server) to hmac(key, "s2c".toByteArray(), client, server)

internal class LinkServer(
    private val name: String, private val computer: Boolean, private val sends: Boolean, private val wifi: String? = null,
    private val hosts: () -> List<String> = ::lanHosts, private val now: () -> Long = System::currentTimeMillis, bind: InetAddress? = null,
) : Closeable {
    private val socket = ServerSocket(0, 8, bind)
    private val codes = ArrayList<Pair<ByteArray, Long>>()
    private var taken = false

    val port get() = socket.localPort

    fun issue(): LinkCode = synchronized(codes) {
        val code = LinkCode(hosts(), socket.localPort, name, randomBytes(32), now() + CodeLifeMs, sends, computer, wifi)
        codes.removeAll { it.second <= now() }
        codes += code.key to code.expires
        code
    }

    fun accept(): LinkChannel {
        while (true) {
            val client = socket.accept()
            if (!isPrivate(client.inetAddress)) { client.close(); continue }
            val channel = try { handshake(client) } catch (_: Exception) { null }
            if (channel != null) return channel
            client.close()
        }
    }

    private fun handshake(client: Socket): LinkChannel? {
        client.soTimeout = HandshakeMs
        client.tcpNoDelay = true
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val magic = ByteArray(Magic.size).also(input::readFully)
        if (!magic.contentEquals(Magic)) {
            if (magic.copyOf(3).contentEquals(Magic.copyOf(3))) output.write(2)
            return null
        }
        val nonce = ByteArray(32).also(input::readFully)
        val proof = ByteArray(32).also(input::readFully)
        val key = synchronized(codes) {
            codes.firstOrNull { (k, expires) -> !taken && now() < expires && MessageDigest.isEqual(proof, hmac(k, "client".toByteArray(), nonce)) }?.first
        }
        if (key == null) { output.write(1); output.flush(); return null }
        val mine = randomBytes(32)
        output.write(byteArrayOf(0) + mine + hmac(key, "server".toByteArray(), nonce, mine))
        output.flush()
        synchronized(codes) { taken = true; codes.clear() }
        val (fromClient, toClient) = keys(key, nonce, mine)
        return LinkChannel(client, toClient, fromClient)
    }

    override fun close() { runCatching { socket.close() } }
}

internal fun clientHandshake(socket: Socket, key: ByteArray): LinkChannel {
    socket.soTimeout = HandshakeMs
    socket.tcpNoDelay = true
    val nonce = randomBytes(32)
    val output = socket.getOutputStream()
    output.write(Magic + nonce + hmac(key, "client".toByteArray(), nonce))
    output.flush()
    val input = DataInputStream(socket.getInputStream())
    when (input.read()) {
        0 -> Unit
        1 -> throw LinkFailure(LinkProblem.Expired)
        2 -> throw LinkFailure(LinkProblem.Version)
        else -> throw LinkFailure(LinkProblem.Expired)
    }
    val theirs = ByteArray(32).also(input::readFully)
    val proof = ByteArray(32).also(input::readFully)
    if (!MessageDigest.isEqual(proof, hmac(key, "server".toByteArray(), nonce, theirs))) throw LinkFailure(LinkProblem.Damaged)
    val (toServer, fromServer) = keys(key, nonce, theirs)
    return LinkChannel(socket, toServer, fromServer)
}

internal suspend fun linkConnect(code: LinkCode, connectMs: Int = 3000, now: () -> Long = System::currentTimeMillis): LinkChannel {
    if (code.version != LinkVersion) throw LinkFailure(LinkProblem.Version)
    val addresses = code.hosts.mapNotNull(::privateHost)
    val first = CompletableDeferred<Socket?>()
    val left = AtomicInteger(addresses.size)
    if (addresses.isEmpty()) first.complete(null)
    for (address in addresses) CoroutineScope(Dispatchers.IO).launch {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(address, code.port), connectMs)
            if (!first.complete(s)) s.close()
        } catch (_: IOException) {
            s.close()
        }
        if (left.decrementAndGet() == 0) first.complete(null)
    }
    val socket = try { first.await() } catch (e: Throwable) { if (!first.completeExceptionally(e)) runCatching { first.await()?.close() }; throw e }
        ?: throw LinkFailure(if (now() > code.expires) LinkProblem.Expired else LinkProblem.Unreachable)
    return try {
        runInterruptible(Dispatchers.IO) { clientHandshake(socket, code.key) }
    } catch (e: Throwable) {
        socket.close()
        throw if (e is IOException && e !is LinkFailure) LinkFailure(LinkProblem.Unreachable) else e
    }
}

private inline fun <T> trusted(read: () -> T): T = try { read() } catch (e: RuntimeException) { throw LinkFailure(LinkProblem.Damaged) }

internal class Peer(val id: String, val name: String, val computer: Boolean, val sends: Boolean) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("computer", computer).put("sends", sends)

    companion object {
        fun read(json: JSONObject) = trusted { Peer(json.getString("id").take(64), json.optString("name").take(64), json.optBoolean("computer"), json.optBoolean("sends")) }
    }
}

internal class Offer(val title: String, val size: Long, val pictures: Int, val project: String, val back: String? = null) {
    fun json(): JSONObject = JSONObject().put("title", title).put("size", size).put("pictures", pictures).put("project", project).apply { if (back != null) put("back", back) }

    companion object {
        fun read(json: JSONObject) = trusted {
            Offer(
                json.optString("title").take(200), json.getLong("size").also { require(it in 0..ImportLimits().unpacked) }, json.optInt("pictures").coerceAtLeast(0),
                json.getString("project").take(64), json.optString("back").takeIf { ProjectId.matches(it) },
            )
        }
    }
}

internal class Arrival(val plan: String, val target: String? = null, val name: String? = null) {
    fun json(): JSONObject = JSONObject().put("plan", plan).apply { if (name != null) put("name", name) }

    companion object {
        const val New = "new"
        const val Replace = "replace"
        const val Both = "both"
        fun read(json: JSONObject) = Arrival(json.optString("plan", New).takeIf { it in setOf(New, Replace, Both) } ?: New, null, json.optString("name").take(240).ifBlank { null })
    }
}

internal suspend fun greet(channel: LinkChannel, me: Peer, client: Boolean): Peer = runInterruptible(Dispatchers.IO) {
    channel.timeoutMs = HandshakeMs
    if (client) {
        channel.send(LinkChannel.Hello, me.json())
        Peer.read(channel.expect(LinkChannel.Hello).json())
    } else {
        val other = Peer.read(channel.expect(LinkChannel.Hello).json())
        if (other.sends == me.sends) {
            channel.fail(LinkProblem.SameMode)
            throw LinkFailure(LinkProblem.SameMode)
        }
        channel.send(LinkChannel.Hello, me.json())
        other
    }
}

internal suspend fun sendProject(channel: LinkChannel, offer: Offer, file: File, onPlan: (Arrival) -> Unit, onProgress: (Long) -> Unit): String = coroutineScope {
    val plan = runInterruptible(Dispatchers.IO) {
        channel.timeoutMs = ReplyMs
        channel.send(LinkChannel.Offer, offer.json())
        Arrival.read(channel.expect(LinkChannel.Plan).json())
    }
    onPlan(plan)
    channel.timeoutMs = ImportMs
    val reply = async(Dispatchers.IO) {
        runCatching { channel.receive() }.onSuccess { if (it.type != LinkChannel.Done) channel.close() }
    }
    val last = AtomicLong(System.nanoTime())
    val watchdog = launch { while (true) { delay(5_000); if (System.nanoTime() - last.get() > StallNs) { channel.close(); break } } }
    try {
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(ChunkBytes)
                var sent = 0L
                while (!reply.isCompleted) {
                    ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    channel.send(LinkChannel.Data, buffer, 0, n)
                    sent += n
                    last.set(System.nanoTime())
                    onProgress(sent)
                }
            }
            if (!reply.isCompleted) channel.send(LinkChannel.End, digest.digest())
        }
    } catch (e: IOException) {
        if (withTimeoutOrNull(1_000) { reply.await() } == null) channel.close()
    } finally {
        watchdog.cancel()
    }
    val frame = reply.await().getOrElse { throw it as? LinkFailure ?: LinkFailure(LinkProblem.Lost) }
    when (frame.type) {
        LinkChannel.Done -> trusted { frame.json().getString("project").take(64) }
        LinkChannel.Cancel -> throw LinkFailure(LinkProblem.Cancelled)
        LinkChannel.Fail -> frame.json().let { throw LinkFailure(runCatching { LinkProblem.valueOf(it.optString("problem")) }.getOrDefault(LinkProblem.Failed), it.optString("detail").ifBlank { null }) }
        else -> throw LinkFailure(LinkProblem.Damaged)
    }
}

internal suspend fun receiveProject(
    channel: LinkChannel, dir: File, plan: (Offer) -> Arrival, onProgress: (Long) -> Unit, import: suspend (File, Offer, Arrival) -> String,
): String {
    val (offer, arrival) = runInterruptible(Dispatchers.IO) {
        channel.timeoutMs = ReplyMs
        val offer = Offer.read(channel.expect(LinkChannel.Offer).json())
        val arrival = plan(offer)
        channel.send(LinkChannel.Plan, arrival.json())
        offer to arrival
    }
    dir.mkdirs()
    val file = File(dir, "receive-${UUID.randomUUID()}.part")
    try {
        runInterruptible(Dispatchers.IO) {
            channel.timeoutMs = DataMs
            val digest = MessageDigest.getInstance("SHA-256")
            var got = 0L
            file.outputStream().buffered(ChunkBytes).use { out ->
                while (true) {
                    val frame = channel.receive()
                    when (frame.type) {
                        LinkChannel.Data -> {
                            got += frame.body.size
                            if (got > offer.size) throw LinkFailure(LinkProblem.Damaged)
                            digest.update(frame.body)
                            out.write(frame.body)
                            onProgress(got)
                        }
                        LinkChannel.End -> {
                            if (got != offer.size || !MessageDigest.isEqual(digest.digest(), frame.body)) throw LinkFailure(LinkProblem.Damaged)
                            break
                        }
                        LinkChannel.Cancel -> throw LinkFailure(LinkProblem.Cancelled)
                        else -> throw LinkFailure(LinkProblem.Damaged)
                    }
                }
            }
        }
        val id = try {
            import(file, offer, arrival)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { channel.fail(LinkProblem.Failed, (e as? LinkFailure)?.detail ?: e.message) }
            throw e
        }
        withContext(Dispatchers.IO) { runCatching { channel.send(LinkChannel.Done, JSONObject().put("project", id)) } }
        return id
    } catch (e: LinkFailure) {
        if (e.problem == LinkProblem.Damaged) withContext(Dispatchers.IO) { channel.fail(LinkProblem.Damaged) }
        throw e
    } finally {
        file.delete()
    }
}

internal class SentRecord(val peer: String, val peerProject: String, val hash: String)

internal class SentProjects(private val file: File) {
    @Synchronized
    private fun all(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    operator fun get(id: String): SentRecord? = all().optJSONObject(id)?.let { SentRecord(it.optString("peer"), it.optString("project"), it.optString("hash")) }

    @Synchronized
    operator fun set(id: String, record: SentRecord) {
        val json = all().put(id, JSONObject().put("peer", record.peer).put("project", record.peerProject).put("hash", record.hash))
        runCatching { writeAtomically(file, json.toString()) }
    }
}

private val SavedStamp = Regex(""" modifiedTime="[^"]*"""")

internal fun contentHash(xml: String): String {
    val normal = SavedStamp.replace(projectScene(xml)?.toXml() ?: xml, "")
    return MessageDigest.getInstance("SHA-256").digest(normal.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun arrivalPlan(store: ProjectStore, records: SentProjects, offer: Offer, sender: Peer, copyName: (String) -> String): Arrival {
    val target = offer.back?.takeIf { ProjectId.matches(it) && store.exists(it) } ?: return Arrival(Arrival.New)
    val record = records[target]?.takeIf { it.peer == sender.id && it.peerProject == offer.project } ?: return Arrival(Arrival.New)
    val text = store.text(target) ?: return Arrival(Arrival.New)
    return if (contentHash(text) == record.hash) Arrival(Arrival.Replace, target) else Arrival(Arrival.Both, target, copyName(offer.title))
}

internal fun arrive(store: ProjectStore, records: SentProjects, imported: String, arrival: Arrival, sender: Peer, offer: Offer): String {
    val id = when (arrival.plan) {
        Arrival.Replace -> arrival.target!!.also { store.replaceWith(it, imported) }
        Arrival.Both -> imported.also { id -> store.text(id)?.let(::projectScene)?.let { store.save(id, it.withTitle(arrival.name ?: it.title)) } }
        else -> imported
    }
    store.text(id)?.let { records[id] = SentRecord(sender.id, offer.project, contentHash(it)) }
    return id
}

internal fun pictureCount(scene: Scene): Int = scene.media.count { it.type.startsWith("image/") || it.type.startsWith("video/") }

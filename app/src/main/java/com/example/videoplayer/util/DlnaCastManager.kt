package com.example.videoplayer.util

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.videoplayer.data.model.MediaItem
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.Executors

data class DlnaDevice(
    val friendlyName: String,
    val location: String,
    val avTransportUrl: String,
    val renderingControlUrl: String? = null
)

object DlnaCastManager {
    // Discovered devices list
    val devices = mutableStateListOf<DlnaDevice>()
    
    // Casting states
    var selectedDevice by mutableStateOf<DlnaDevice?>(null)
    var isCasting by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var volume by mutableStateOf(50)
    var castUrl by mutableStateOf("")
    
    var currentVideoFile: File? = null
    private var localServer: LocalHttpServer? = null
    private var discoveryJob: Job? = null
    private var castJob: Job? = null
    private var pollingJob: Job? = null
    @Volatile private var discoverySocket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Start scanning local network devices
    fun startDiscovery(context: Context) {
        devices.clear()
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            var multicastLock: WifiManager.MulticastLock? = null
            var socket: DatagramSocket? = null
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("DlnaSsdpLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                
                val activeSocket = DatagramSocket()
                socket = activeSocket
                discoverySocket = activeSocket
                activeSocket.soTimeout = 3000
                
                // standard MediaRenderer search
                val msearch = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                        "\r\n"
                
                // ssdp:all search fallback
                val msearchAll = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: ssdp:all\r\n" +
                        "\r\n"
                
                val targetAddress = InetAddress.getByName("239.255.255.250")
                val packet1 = DatagramPacket(msearch.toByteArray(), msearch.length, targetAddress, 1900)
                val packet2 = DatagramPacket(msearchAll.toByteArray(), msearchAll.length, targetAddress, 1900)
                
                activeSocket.send(packet1)
                delay(100)
                activeSocket.send(packet2)
                
                val buffer = ByteArray(2048)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                val startTime = System.currentTimeMillis()
                val processedLocations = mutableSetOf<String>()
                
                while (System.currentTimeMillis() - startTime < 6000 && isActive) {
                    try {
                        activeSocket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length)
                        val location = getHeaderValue(response, "LOCATION")
                        if (location != null && processedLocations.add(location)) {
                            launch {
                                val device = fetchDeviceDetails(location)
                                if (device != null) {
                                    withContext(Dispatchers.Main) {
                                        if (devices.none { it.location == device.location }) {
                                            devices.add(device)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
                if (discoverySocket === socket) discoverySocket = null
                try {
                    multicastLock?.release()
                } catch (e: Exception) {}
            }
        }
    }
    
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        discoverySocket?.close()
        discoverySocket = null
    }
    
    private suspend fun fetchDeviceDetails(locationUrl: String): DlnaDevice? = withContext(Dispatchers.IO) {
        try {
            val url = URL(locationUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            
            val friendlyName = xml.substringAfter("<friendlyName>").substringBefore("</friendlyName>")
            if (friendlyName == xml) return@withContext null
            
            val avTransportIndex = xml.indexOf("urn:schemas-upnp-org:service:AVTransport:1")
            if (avTransportIndex == -1) return@withContext null
            
            val avTransportBlock = xml.substring(avTransportIndex)
            val avtControlUrlPath = avTransportBlock.substringAfter("<controlURL>").substringBefore("</controlURL>")
            val avTransportUrl = resolveUrl(locationUrl, avtControlUrlPath)
            
            val renderingControlIndex = xml.indexOf("urn:schemas-upnp-org:service:RenderingControl:1")
            val renderingControlUrl = if (renderingControlIndex != -1) {
                val rcBlock = xml.substring(renderingControlIndex)
                val rcControlUrlPath = rcBlock.substringAfter("<controlURL>").substringBefore("</controlURL>")
                resolveUrl(locationUrl, rcControlUrlPath)
            } else null
            
            DlnaDevice(friendlyName.trim(), locationUrl, avTransportUrl, renderingControlUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun getHeaderValue(response: String, headerName: String): String? {
        val lines = response.split("\r\n", "\n")
        for (line in lines) {
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].trim().equals(headerName, ignoreCase = true)) {
                return parts[1].trim()
            }
        }
        return null
    }
    
    private fun resolveUrl(baseUrlStr: String, relativePath: String): String {
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
            return relativePath
        }
        val baseUrl = URL(baseUrlStr)
        val portStr = if (baseUrl.port != -1) ":${baseUrl.port}" else ""
        val base = "${baseUrl.protocol}://${baseUrl.host}$portStr"
        return if (relativePath.startsWith("/")) {
            base + relativePath
        } else {
            val path = baseUrl.path
            val dir = path.substringBeforeLast("/", "")
            "$base$dir/$relativePath"
        }
    }
    
    // Connect to DLNA device and start streaming
    fun startCast(device: DlnaDevice, video: MediaItem, startPosMs: Long) {
        castJob?.cancel()
        pollingJob?.cancel()
        selectedDevice = device
        isCasting = true
        isPlaying = true
        position = startPosMs
        duration = video.duration
        currentVideoFile = File(video.path)
        
        // Start local server
        localServer?.stop()
        val port = 8085
        localServer = LocalHttpServer({ currentVideoFile }, port).apply { start() }
        
        val localIp = getLocalIpAddress() ?: "127.0.0.1"
        val videoUrl = "http://$localIp:$port/video_${video.id}.mp4"
        castUrl = videoUrl
        
        castJob = scope.launch {
            // Set URI with DIDL metadata
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                    "<item id=\"0\" parentID=\"-1\" restricted=\"false\">" +
                    "<dc:title>${escapeXml(video.displayName)}</dc:title>" +
                    "<upnp:class>object.item.videoItem</upnp:class>" +
                    "<res protocolInfo=\"http-get:*:video/mp4:*\">$videoUrl</res>" +
                    "</item>" +
                    "</DIDL-Lite>"
            
            val setUriArgs = mapOf(
                "InstanceID" to "0",
                "CurrentURI" to videoUrl,
                "CurrentURIMetaData" to escapeXml(didl)
            )
            
            sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1", "SetAVTransportURI", setUriArgs)
            delay(800) // wait for device buffer initialization
            
            // Seek if position > 0
            if (startPosMs > 0) {
                val seekArgs = mapOf(
                    "InstanceID" to "0",
                    "Unit" to "REL_TIME",
                    "Target" to formatMsToHms(startPosMs)
                )
                sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Seek", seekArgs)
                delay(300)
            }
            
            // Play
            val playArgs = mapOf(
                "InstanceID" to "0",
                "Speed" to "1"
            )
            sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Play", playArgs)
            
            // Start periodic polling for progress sync
            if (isActive && isCasting && selectedDevice == device) {
                startPolling()
            }
        }
    }
    
    fun play() {
        val device = selectedDevice ?: return
        isPlaying = true
        scope.launch {
            val playArgs = mapOf(
                "InstanceID" to "0",
                "Speed" to "1"
            )
            sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Play", playArgs)
        }
    }
    
    fun pause() {
        val device = selectedDevice ?: return
        isPlaying = false
        scope.launch {
            val pauseArgs = mapOf(
                "InstanceID" to "0"
            )
            sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Pause", pauseArgs)
        }
    }
    
    fun seek(posMs: Long) {
        val device = selectedDevice ?: return
        position = posMs
        scope.launch {
            val seekArgs = mapOf(
                "InstanceID" to "0",
                "Unit" to "REL_TIME",
                "Target" to formatMsToHms(posMs)
            )
            sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Seek", seekArgs)
        }
    }
    
    fun setCastVolume(vol: Int) {
        val device = selectedDevice ?: return
        volume = vol.coerceIn(0, 100)
        val rcUrl = device.renderingControlUrl ?: return
        scope.launch {
            val volArgs = mapOf(
                "InstanceID" to "0",
                "Channel" to "Master",
                "DesiredVolume" to volume.toString()
            )
            sendSoapAction(rcUrl, "urn:schemas-upnp-org:service:RenderingControl:1", "SetVolume", volArgs)
        }
    }
    
    fun stopCast() {
        val device = selectedDevice
        castJob?.cancel()
        castJob = null
        pollingJob?.cancel()
        pollingJob = null
        
        if (device != null) {
            scope.launch {
                val stopArgs = mapOf(
                    "InstanceID" to "0"
                )
                sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Stop", stopArgs)
            }
        }
        
        localServer?.stop()
        localServer = null
        selectedDevice = null
        isCasting = false
        isPlaying = false
    }
    
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && isCasting) {
                val device = selectedDevice ?: break
                val args = mapOf("InstanceID" to "0")
                val response = sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1", "GetPositionInfo", args)
                if (response != null) {
                    val relTimeStr = response.substringAfter("<RelTime>").substringBefore("</RelTime>")
                    val durationStr = response.substringAfter("<TrackDuration>").substringBefore("</TrackDuration>")
                    withContext(Dispatchers.Main) {
                        if (relTimeStr.isNotEmpty() && relTimeStr != response) {
                            position = parseHmsToMs(relTimeStr)
                        }
                        if (durationStr.isNotEmpty() && durationStr != response && durationStr != "00:00:00") {
                            duration = parseHmsToMs(durationStr)
                        }
                    }
                }
                delay(1500)
            }
        }
    }
    
    private fun sendSoapAction(controlUrl: String, serviceType: String, actionName: String, args: Map<String, String>): String? {
        return try {
            val url = URL(controlUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$serviceType#$actionName\"")
            conn.doOutput = true

            val xmlArgs = StringBuilder()
            for ((k, v) in args) {
                xmlArgs.append("<$k>$v</$k>")
            }

            val soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                    "  <s:Body>\n" +
                    "    <u:$actionName xmlns:u=\"$serviceType\">\n" +
                    "      $xmlArgs\n" +
                    "    </u:$actionName>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>"

            conn.outputStream.use { os ->
                os.write(soapBody.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun escapeXml(value: String): String {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
    
    private fun formatMsToHms(ms: Long): String {
        val totalSec = ms / 1000
        val hr = totalSec / 3600
        val min = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hr, min, sec)
    }
    
    private fun parseHmsToMs(hms: String): Long {
        val parts = hms.split(":")
        if (parts.size != 3) return 0L
        val hr = parts[0].toLongOrNull() ?: 0L
        val min = parts[1].toLongOrNull() ?: 0L
        val sec = parts[2].toLongOrNull() ?: 0L
        return (hr * 3600 + min * 60 + sec) * 1000
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}

class LocalHttpServer(private val fileProvider: () -> File?, private val port: Int) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val threadPool = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        threadPool.execute {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    threadPool.execute {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                // Socket closed or server stopped
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        threadPool.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream().bufferedReader()
            val output = BufferedOutputStream(socket.getOutputStream())
            
            // Read HTTP request line
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendError(output, 400, "Bad Request")
                socket.close()
                return
            }
            
            // Read headers to look for Range
            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }
            
            val file = fileProvider()
            if (file == null || !file.exists() || !file.isFile) {
                sendError(output, 404, "Not Found")
                socket.close()
                return
            }
            
            val fileLength = file.length()
            val mimeType = getMimeType(file.name)
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                // Parse Range: bytes=start-end
                val rangeStr = rangeHeader.substringAfter("bytes=")
                val rangeParts = rangeStr.split("-")
                val start = rangeParts[0].toLongOrNull() ?: 0L
                val requestedEnd = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                    rangeParts[1].toLongOrNull() ?: (fileLength - 1)
                } else {
                    fileLength - 1
                }
                if (start < 0L || start >= fileLength || requestedEnd < start) {
                    val header = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */$fileLength\r\n" +
                        "Content-Length: 0\r\n\r\n"
                    output.write(header.toByteArray())
                    output.flush()
                    return
                }
                val end = requestedEnd.coerceAtMost(fileLength - 1)
                
                val contentRange = "bytes $start-$end/$fileLength"
                val contentLength = end - start + 1
                
                // Write HTTP 206 Partial Content response
                val header = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: $mimeType\r\n" +
                        "Content-Length: $contentLength\r\n" +
                        "Content-Range: $contentRange\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n"
                output.write(header.toByteArray())
                
                // Write partial file bytes from start to end
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(64 * 1024)
                    var bytesRemaining = contentLength
                    while (bytesRemaining > 0) {
                        val readSize = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                        val read = raf.read(buffer, 0, readSize)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesRemaining -= read
                    }
                }
            } else {
                // Write HTTP 200 OK response
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $mimeType\r\n" +
                        "Content-Length: $fileLength\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n"
                output.write(header.toByteArray())
                
                // Write full file
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = fis.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            output.flush()
        } catch (e: SocketException) {
            // Client closed connection, normal for streaming media players
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun sendError(out: BufferedOutputStream, code: Int, msg: String) {
        val response = "HTTP/1.1 $code $msg\r\n" +
                "Content-Length: ${msg.length}\r\n" +
                "\r\n" +
                msg
        try {
            out.write(response.toByteArray())
            out.flush()
        } catch (e: Exception) {}
    }

    private fun getMimeType(filename: String): String {
        return when (filename.substringAfterLast(".").lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "video/mp4"
        }
    }
}

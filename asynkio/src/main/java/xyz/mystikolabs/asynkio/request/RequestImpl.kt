package xyz.mystikolabs.asynkio.request

import org.json.JSONArray
import org.json.JSONObject
import xyz.mystikolabs.asynkio.extensions.putAllIfAbsentWithNull
import xyz.mystikolabs.asynkio.helper.CaseInsensitiveMutableMap
import xyz.mystikolabs.asynkio.helper.Parameters
import java.io.StringWriter
import java.net.IDN
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import org.json.*
import xyz.mystikolabs.asynkio.helper.Auth
import java.io.ByteArrayOutputStream


class RequestImpl internal constructor(
    override val method: String,
    url: String,
    override val params: Map<String, String>,
    headers: Map<String, String?>,
    override val auth: Auth?,
    data: Any?,
    override val json: Any?,
    override val timeout: Double,
    allowRedirects: Boolean?,
    override val stream: Boolean
) : Request {

    companion object {
        val DEFAULT_HEADERS = mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate"
        )
        val DEFAULT_DATA_HEADERS = mapOf(
            "Content-Type" to "text/plain"
        )
        val DEFAULT_FORM_HEADERS = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded"
        )
        val DEFAULT_UPLOAD_HEADERS = mapOf(
            "Content-Type" to "multipart/form-data; boundary=%s"
        )
        val DEFAULT_JSON_HEADERS = mapOf(
            "Content-Type" to "application/json"
        )
    }

    // Request
    override val url: String
    override val headers: Map<String, String>
    override val data: Any?
    override val allowRedirects = allowRedirects ?: (this.method != "HEAD")

    private var _body: ByteArray? = null
    override val body: ByteArray
        get() {
            if (this._body == null) {
                val requestData = this.data
                if (requestData == null) {
                    this._body = ByteArray(0)
                    return this._body
                        ?: throw IllegalStateException("Set to null by another thread")
                }
                val data: Any? = if (requestData != null) {
                    if (requestData is Map<*, *> && requestData !is Parameters) {
                        Parameters(requestData.mapKeys { it.key.toString() }.mapValues { it.value.toString() })
                    } else {
                        requestData
                    }
                } else {
                    null
                }
                if (data != null) {
                    require(data is Map<*, *>) { "data must be a Map" }
                }
                val bytes = ByteArrayOutputStream()
                bytes.write(data.toString().toByteArray())
                this._body = bytes.toByteArray()
            }
            return this._body ?: throw IllegalStateException("Set to null by another thread")
        }


    init {
        this.url = this.makeRoute(url)
        if (URI(this.url).scheme !in setOf("http", "https")) {
            throw IllegalArgumentException("Invalid schema. Only http:// and https:// are supported.")
        }
        val json = this.json
        val mutableHeaders = CaseInsensitiveMutableMap(headers.toSortedMap())

        if (json == null) {
            this.data = data
        } else {
            this.data = this.coerceToJSON(json)
            mutableHeaders.putAllIfAbsentWithNull(RequestImpl.DEFAULT_JSON_HEADERS)
        }
        mutableHeaders.putAllIfAbsentWithNull(RequestImpl.DEFAULT_HEADERS)

        val auth = this.auth
        if (auth != null) {
            val header = auth.header
            mutableHeaders[header.first] = header.second
        }
        val nonNullHeaders: MutableMap<String, String> =
            mutableHeaders.filterValues { it != null }.mapValues { it.value!! }.toSortedMap()

        this.headers = CaseInsensitiveMutableMap(nonNullHeaders)
    }

    private fun coerceToJSON(any: Any): String {
        if (any is JSONObject || any is JSONArray) {
            return any.toString()
        } else if (any is Map<*, *>) {
            return JSONObject(any.mapKeys { it.key.toString() }).toString()
        } else if (any is Collection<*>) {
            return JSONArray(any).toString()
        } else if (any is Iterable<*>) {
            return any.withJSONWriter { jsonWriter, _ ->
                jsonWriter.array()
                for (thing in any) {
                    jsonWriter.value(thing)
                }
                jsonWriter.endArray()
            }
        } else if (any is Array<*>) {
            return JSONArray(any).toString()
        } else {
            throw IllegalArgumentException("Could not coerce ${any.javaClass.simpleName} to JSON.")
        }
    }

    private fun <T> T.withJSONWriter(converter: (JSONStringer, T) -> Unit): String {
        val stringWriter = StringWriter()
        val writer = JSONStringer()
        converter(writer, this)
        return stringWriter.toString()
    }

    private fun URL.toIDN(): URL {
        val newHost = IDN.toASCII(this.host)
        this.javaClass.getDeclaredField("host").apply { this.isAccessible = true }
            .set(this, newHost)
        this.javaClass.getDeclaredField("authority")
            .apply { this.isAccessible = true }
            .set(this, if (this.port == -1) this.host else "${this.host}:${this.port}")
        val query = if (this.query == null) {
            null
        } else {
            URLDecoder.decode(this.query, "UTF-8")
        }
        return URL(
            URI(
                this.protocol,
                this.userInfo,
                this.host,
                this.port,
                this.path,
                query,
                this.ref
            ).toASCIIString()
        )
    }

    private fun makeRoute(route: String) =
        URL(
            route +
                    if (this.params.isNotEmpty()) "?${Parameters(this.params)}" else ""
        ).toIDN().toString()

}

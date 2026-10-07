import groovy.json.JsonSlurper
import org.apache.http.client.config.RequestConfig
import org.apache.http.config.ConnectionConfig
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.ssl.SSLContextBuilder
import org.apache.http.ssl.TrustStrategy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction

// Request data is encoded so JMeter expressions and Groovy code in literal headers cannot run.
def data = new JsonSlurper().parse(Base64.decoder.decode(args[0]))
byte[] body = Base64.decoder.decode(data.body as String)
int block = data.chunkBytes as int
def entity = new ByteArrayEntity(body) {
    @Override void writeTo(OutputStream output) {
        if (block == 0) { output.write(body); return }
        for (int offset = 0; offset < body.length; offset += block) {
            output.write(body, offset, Math.min(block, body.length - offset))
            output.flush()
        }
    }
}
entity.setChunked(block > 0)
def request = RequestBuilder.create(data.method as String).setUri(data.url as String).setEntity(entity)
data.headers.each { name, value -> request.addHeader(name as String, value as String) }
def httpRequest = request.build()
def builder = HttpClients.custom().disableAutomaticRetries().disableContentCompression().disableRedirectHandling()
builder.setDefaultConnectionConfig(ConnectionConfig.custom().setCharset(StandardCharsets.UTF_8)
    .setMalformedInputAction(CodingErrorAction.REPORT).setUnmappableInputAction(CodingErrorAction.REPORT).build())
if (data.insecure) {
    def ssl = SSLContextBuilder.create().loadTrustMaterial(null, { chain, authType -> true } as TrustStrategy).build()
    builder.setSSLSocketFactory(new SSLConnectionSocketFactory(ssl, NoopHostnameVerifier.INSTANCE))
}
builder.setDefaultRequestConfig(RequestConfig.custom()
    .setConnectTimeout(data.connectTimeout as int)
    .setConnectionRequestTimeout(data.connectTimeout as int)
    .setSocketTimeout(data.requestTimeout as int).build())
def client = builder.build()
def deadline = Executors.newSingleThreadScheduledExecutor()
def expired = new AtomicBoolean(false)
def timer = deadline.schedule({ expired.set(true); httpRequest.abort() } as Runnable,
    data.requestTimeout as long, TimeUnit.MILLISECONDS)
try {
    SampleResult.setURL(new URL(data.url as String))
    SampleResult.setSamplerData((data.method as String) + ' ' + data.url + '\nRequest bytes: ' + body.length)
    SampleResult.setSentBytes(body.length)
    SampleResult.setRequestHeaders(data.headers.collect { name, value -> name + ': ' + value }.join('\n'))
    client.execute(httpRequest).withCloseable { response ->
        int status = response.statusLine.statusCode
        SampleResult.setResponseCode(Integer.toString(status))
        SampleResult.setResponseMessage(response.statusLine.reasonPhrase)
        SampleResult.setResponseHeaders(response.allHeaders.collect { it.toString() }.join('\n'))
        if (response.entity != null) {
            response.entity.content.withCloseable { input -> SampleResult.setResponseData(input.bytes) }
            if (response.entity.contentType != null) SampleResult.setContentType(response.entity.contentType.value)
        }
        SampleResult.setSuccessful(status >= 200 && status < 400 && !expired.get())
    }
} catch (Exception failure) {
    SampleResult.setSuccessful(false)
    SampleResult.setResponseCode(expired.get() ? 'TIMEOUT' : failure.class.simpleName)
    SampleResult.setResponseMessage(expired.get() ? 'Request deadline exceeded' : (failure.message ?: failure.toString()))
} finally {
    timer.cancel(false)
    deadline.shutdownNow()
    client.close()
}
return null

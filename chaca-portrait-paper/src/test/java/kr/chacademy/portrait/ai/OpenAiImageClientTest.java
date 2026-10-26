package kr.chacademy.portrait.ai;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiImageClientTest {

    @Test
    void parsesImageAndUsage() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        String body = "{\"created\":1,\"data\":[{\"b64_json\":\"" + b64 + "\"}],\"usage\":{\"input_tokens\":5000,"
                + "\"input_tokens_details\":{\"image_tokens\":4800,\"text_tokens\":200},\"output_tokens\":1584,\"total_tokens\":6584}}";
        var r = OpenAiImageClient.parseImage(body);
        assertEquals(4, r.png().length);
        assertEquals(200, r.usage().textInput());
        assertEquals(4800, r.usage().imageInput());
        assertEquals(1584, r.usage().output());
        assertTrue(r.usage().known());
    }

    @Test
    void missingUsageIsUnknown() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(new byte[]{1});
        var r = OpenAiImageClient.parseImage("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}");
        assertFalse(r.usage().known());
        var e = assertThrows(OpenAiImageClient.ApiException.class, () -> OpenAiImageClient.parseImage("{\"data\":[]}"));
        assertTrue(e.billedUnknown);
    }

    @Test
    void parsesResponseText() throws Exception {
        String body = "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"silver hair, blue tie\"}]}],"
                + "\"usage\":{\"input_tokens\":900,\"output_tokens\":40}}";
        var t = OpenAiImageClient.parseResponseText(body);
        assertEquals("silver hair, blue tie", t.text());
        assertEquals(900, t.inputTokens());
        assertEquals(40, t.outputTokens());
    }

    @Test
    void connectionFailuresAreRetryableAndNotBilled() {
        for (Throwable t : new Throwable[]{
                new java.net.http.HttpConnectTimeoutException("connect"),
                new java.net.ConnectException("refused"),
                new java.net.UnknownHostException("dns"),
                new java.util.concurrent.ExecutionException(new java.net.ConnectException("refused")),
                new java.util.concurrent.CompletionException(new java.net.http.HttpConnectTimeoutException("connect"))}) {
            var e = OpenAiImageClient.classify(t);
            assertEquals(OpenAiImageClient.Type.RETRYABLE, e.type, String.valueOf(t));
            assertFalse(e.billedUnknown, String.valueOf(t));
            assertTrue(e.retryable());
            assertEquals(0, e.status);
        }
    }

    @Test
    void timeoutsAfterSendAreRetryableButMayBeBilled() {
        for (Throwable t : new Throwable[]{
                new java.net.http.HttpTimeoutException("read"),
                new java.util.concurrent.TimeoutException(),
                new java.io.IOException("connection reset"),
                new java.util.concurrent.ExecutionException(new java.io.IOException("Response exceeds byte limit"))}) {
            var e = OpenAiImageClient.classify(t);
            assertEquals(OpenAiImageClient.Type.RETRYABLE, e.type, String.valueOf(t));
            assertTrue(e.billedUnknown, String.valueOf(t));
        }
        var same = new OpenAiImageClient.ApiException("x", 400, false, OpenAiImageClient.Type.TERMINAL);
        assertSame(same, OpenAiImageClient.classify(new java.util.concurrent.ExecutionException(same)));
    }

    @Test
    void httpStatusClassification() {
        for (int code : new int[]{500, 502, 503, 504}) {
            var e = OpenAiImageClient.httpError(code, "server");
            assertEquals(OpenAiImageClient.Type.RETRYABLE, e.type);
            assertTrue(e.billedUnknown, "5xx는 과금 여부를 모름");
            assertEquals(code, e.status);
        }
        for (int code : new int[]{429, 408, 409}) {
            var e = OpenAiImageClient.httpError(code, "slow down");
            assertEquals(OpenAiImageClient.Type.RETRYABLE, e.type);
            assertFalse(e.billedUnknown, "429 등은 과금 없음");
        }
        for (int code : new int[]{401, 403, 404}) {
            var e = OpenAiImageClient.httpError(code, "nope");
            assertEquals(OpenAiImageClient.Type.CONFIG, e.type);
            assertFalse(e.billedUnknown);
            assertFalse(e.retryable());
        }
        var refused = OpenAiImageClient.httpError(400, "moderation_blocked — Your request was rejected by the safety system.");
        assertEquals(OpenAiImageClient.Type.TERMINAL, refused.type);
        assertFalse(refused.billedUnknown);
        var invalid = OpenAiImageClient.httpError(400, "invalid_value — Invalid value: 'huge'. Supported values are: '1024x1536'.");
        assertEquals(OpenAiImageClient.Type.CONFIG, invalid.type);
        assertFalse(invalid.billedUnknown);
        assertFalse(OpenAiImageClient.httpError(400, null).retryable());
    }

    @Test
    void legacyConstructorIsTerminal() {
        assertEquals(OpenAiImageClient.Type.TERMINAL, new OpenAiImageClient.ApiException("x", 200, true).type);
    }
}

package kr.chacademy.portrait.ai;

import kr.chacademy.portrait.core.PortraitSettings;
import kr.chacademy.portrait.core.CostModel;
import kr.chacademy.portrait.core.PromptBuilder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class Image25RequestTest {
    private PortraitSettings settings(String quality) {
        var c = new YamlConfiguration();
        c.set("image.quality", quality);
        c.set("image.background", "opaque"); // 2.5 must still explicitly request alpha.
        return new PortraitSettings(c);
    }
    private String body(HttpRequest request) throws Exception {
        var future = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] b = new byte[buffer.remaining()]; buffer.get(b); bytes.writeBytes(b); }
            public void onError(Throwable failure) { future.completeExceptionally(failure); }
            public void onComplete() { future.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    @Test void both25ModelsUseNativePngOnBothRequestFormats() throws Exception {
        var client = new OpenAiImageClient();
        for (String model : List.of("gpt-image-2.5-sunburst", "gpt-image-2.5-flare")) {
            var s = settings("medium");
            String json = body(client.jsonEditRequest(s, model, List.of(new byte[]{1,2,3}), "skin", null, false));
            assertTrue(json.contains("\"background\":\"transparent\""));
            assertTrue(json.contains("\"output_format\":\"png\""));
            assertTrue(json.contains("\"quality\":\"medium\""));
            assertTrue(json.contains("1024x1536"));
            assertFalse(json.contains("input_fidelity"));
            String multipart = body(client.multipartEditRequest(s, model, List.of(new byte[]{1,2,3}), "skin", null));
            assertTrue(multipart.contains("name=\"background\"\r\n\r\ntransparent"));
            assertTrue(multipart.contains("name=\"output_format\"\r\n\r\npng"));
            assertFalse(multipart.contains("input_fidelity"));
            assertFalse(PortraitSettings.needsLocalMatte(model));
            assertEquals("skin", PromptBuilder.forModel("skin", model));
        }
    }
    @Test void legacyRequestsKeepTheirOwnOptions() throws Exception {
        var client = new OpenAiImageClient();
        String legacy = body(client.jsonEditRequest(settings("medium"), "gpt-image-1.5", List.of(), "skin", null, false));
        assertTrue(legacy.contains("input_fidelity"));
        assertTrue(PortraitSettings.needsLocalMatte("gpt-image-2"));
        assertEquals("opaque", settings("medium").requestBackground("gpt-image-2"));
        assertFalse(PortraitSettings.supportsQuality("gpt-image-2", "max"));
        assertFalse(PortraitSettings.supportedImageModel("gpt-image-2-typo"));
    }
    @Test void defaultAndExplicitQualityArePreserved() {
        assertEquals("gpt-image-2.5-sunburst", settings("medium").imageModel);
        assertFalse(settings("medium").autoFirstJoin);
        for (String quality : List.of("low","medium","high","auto","xhigh","max")) {
            assertEquals(quality, settings(quality).quality);
            assertTrue(PortraitSettings.supportsQuality("gpt-image-2.5-flare", quality));
        }
    }
    @Test void reservationIsIndependentAndUsageCanExceedIt() {
        String model = "gpt-image-2.5-sunburst";
        var prices = Map.of(model,new CostModel.Prices(5,8,30,0,2));
        var cost = new CostModel(prices,new CostModel.Estimate(1,1,1,1,1,1));
        assertEquals(429000,cost.reserveImage(model,"medium"));
        assertEquals(2,cost.prices(model).cachedImageInput());
        assertEquals(960000,cost.settleImage(model,new CostModel.ImageUsage(4000,5000,30000),429000));
        assertTrue(cost.reserveImage(model,"max") > cost.reserveImage(model,"medium"));
    }
}

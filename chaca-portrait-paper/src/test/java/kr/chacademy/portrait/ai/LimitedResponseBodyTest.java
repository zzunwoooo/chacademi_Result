package kr.chacademy.portrait.ai;
import java.nio.ByteBuffer;import java.util.List;import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class LimitedResponseBodyTest {
 static class Sub implements Flow.Subscription{boolean cancelled;public void request(long n){}public void cancel(){cancelled=true;}}
 @Test void rejectsBeforeAccumulatingOversizedChunk(){var b=new LimitedResponseBody(4);var s=new Sub();b.onSubscribe(s);b.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));b.onNext(List.of(ByteBuffer.wrap(new byte[]{4,5})));assertTrue(s.cancelled);assertTrue(b.getBody().toCompletableFuture().isCompletedExceptionally());b.onComplete();assertTrue(b.getBody().toCompletableFuture().isCompletedExceptionally());}
 @Test void acceptsExactBoundary(){var b=new LimitedResponseBody(4);var s=new Sub();b.onSubscribe(s);b.onNext(List.of(ByteBuffer.wrap(new byte[]{1}),ByteBuffer.wrap(new byte[]{2,3,4})));b.onComplete();assertArrayEquals(new byte[]{1,2,3,4},b.getBody().toCompletableFuture().join());assertFalse(s.cancelled);}
}

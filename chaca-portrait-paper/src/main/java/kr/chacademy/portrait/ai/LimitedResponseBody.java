package kr.chacademy.portrait.ai;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
/** Enforce the limit during HTTP reception, before JSON/base64 allocations. */
public final class LimitedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
 private final int limit; private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
 private final CompletableFuture<byte[]> result=new CompletableFuture<>(); private Flow.Subscription subscription;
 LimitedResponseBody(int limit){this.limit=limit;}
 /** 문자열 대신 바이트 그대로 (스킨 다운로드용). */
 public static HttpResponse.BodyHandler<byte[]> bytes(int limit){return info->new LimitedResponseBody(limit);}
 static HttpResponse.BodyHandler<String> handler(int limit){return info->HttpResponse.BodySubscribers.mapping(new LimitedResponseBody(limit),b->new String(b,StandardCharsets.UTF_8));}
 public CompletionStage<byte[]> getBody(){return result;}
 public void onSubscribe(Flow.Subscription s){if(subscription!=null){s.cancel();return;}subscription=s;s.request(1);}
 public void onNext(List<ByteBuffer> chunks){
  if(result.isDone())return;
  for(ByteBuffer b:chunks){if(b.remaining()>limit-bytes.size()){subscription.cancel();result.completeExceptionally(new IOException("Response exceeds byte limit"));return;}
   byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}
  subscription.request(1);
 }
 public void onError(Throwable t){result.completeExceptionally(t);}
 public void onComplete(){result.complete(bytes.toByteArray());}
}

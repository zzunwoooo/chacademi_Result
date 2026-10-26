package kr.chacademy.portrait.core;

import java.util.Map;

/**
 * 비용 계산 (단위: micro-USD = 100만분의 1달러). 단가가 "USD / 100만 토큰"이므로 토큰 수 × 단가 = micro-USD.
 * 예약은 넉넉한 추정치, 정산은 응답의 실제 usage로 한다.
 */
public final class CostModel {

    /** USD / 100만 토큰. imageOutput은 이미지 모델 출력, textOutput은 텍스트 모델 출력. */
    public record Prices(double textInput, double imageInput, double imageOutput, double textOutput, double cachedImageInput) {
        public Prices(double textInput, double imageInput, double imageOutput, double textOutput) {
            this(textInput, imageInput, imageOutput, textOutput, imageInput);
        }
    }

    public record Estimate(int outLow, int outMedium, int outHigh, int inputImage, int inputText, int describe) {
        public int output(String quality) {
            return switch (quality) {
                case "low" -> outLow;
                case "high", "auto" -> outHigh;
                case "xhigh" -> Math.multiplyExact(outHigh, 2);
                case "max" -> Math.multiplyExact(outHigh, 4);
                default -> outMedium;
            };
        }
    }

    /** 이미지 응답 usage. 모르는 칸은 -1. */
    public record ImageUsage(long textInput, long imageInput, long output) {
        public boolean known() {
            return output >= 0 && (textInput >= 0 || imageInput >= 0);
        }
    }

    private final Map<String, Prices> prices;
    private final Estimate estimate;

    private final Estimate estimate25;
    public CostModel(Map<String, Prices> prices, Estimate estimate) {
        this(prices, estimate, new Estimate(3000, 9000, 18000, 18000, 3000, 3000));
    }
    public CostModel(Map<String, Prices> prices, Estimate estimate, Estimate estimate25) {
        this.prices = prices;
        this.estimate = estimate;
        this.estimate25 = estimate25;
    }

    public boolean hasPrices(String model) {
        return prices.containsKey(model) || prices.containsKey(baseModel(model));
    }

    public Prices prices(String model) {
        Prices p = prices.get(model);
        if (p == null) {
            p = prices.get(baseModel(model));
        }
        return p == null ? new Prices(0, 0, 0, 0) : p;
    }

    /** gpt-image-2-2026-04-21 같은 스냅샷 이름을 단가표 키로. */
    static String baseModel(String model) {
        if (model == null) {
            return "";
        }
        return model.replaceFirst("-\\d{4}-\\d{2}-\\d{2}$", "");
    }

    /** 이미지 한 장 예약액 (micro-USD). */
    public long reserveImage(String model, String quality) {
        Prices p = prices(model);
        Estimate e = PortraitSettings.nativeTransparent(model) ? estimate25 : estimate;
        // Auto may select a higher quality. Reserve the max heuristic for 2.5, without changing the request.
        String reservedQuality = PortraitSettings.nativeTransparent(model) && "auto".equals(quality) ? "max" : quality;
        double micro = e.inputText() * p.textInput() + e.inputImage() * p.imageInput()
                + e.output(reservedQuality) * p.imageOutput();
        return Math.max(1, (long) Math.ceil(micro));
    }

    /** 이미지 한 장 정산액. usage를 모르면 예약액. */
    public long settleImage(String model, ImageUsage usage, long reserved) {
        if (usage == null || !usage.known()) {
            return reserved;
        }
        Prices p = prices(model);
        double micro = Math.max(0, usage.textInput()) * p.textInput()
                + Math.max(0, usage.imageInput()) * p.imageInput()
                + Math.max(0, usage.output()) * p.imageOutput();
        return (long) Math.ceil(micro);
    }

    /** 외형 정리(텍스트 모델) 예약액. */
    public long reserveDescribe(String model, int maxOutput) {
        Prices p = prices(model);
        double micro = estimate.describe() * Math.max(p.textInput(), p.imageInput()) + maxOutput * p.textOutput();
        return Math.max(1, (long) Math.ceil(micro));
    }

    public long settleDescribe(String model, long input, long output, long reserved) {
        if (input < 0 || output < 0) {
            return reserved;
        }
        Prices p = prices(model);
        return (long) Math.ceil(input * Math.max(p.textInput(), p.imageInput()) + output * p.textOutput());
    }

    public static String usd(long micro) {
        return String.format(java.util.Locale.ROOT, "$%.4f", micro / 1_000_000.0);
    }
}

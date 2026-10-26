package kr.chacademy.cutscene.data;

import java.util.List;

/** cutscene.yml 한 개의 내용. 형식은 FORMAT.md 참고. */
public record Cutscene(
        String id,
        String title,
        String font,
        double fontSize,
        int textColor,
        double textX,
        double textY,
        boolean textShadow,
        boolean italic,
        String prefix,
        double typeSpeed,
        double letterbox,
        double letterboxTime,
        double endFade,
        String typeSound,
        double typeSoundVolume,
        String bgm,
        double bgmVolume,
        double bgmStart,
        double bgmFadeIn,
        double bgmFadeOut,
        List<Scene> scenes) {

    public record Scene(
            List<String> images,
            double fps,
            double duration,
            double zoomX,
            double zoomY,
            double zoomFrom,
            double zoomTo,
            String transition,
            double transitionTime,
            List<Line> lines) {
    }

    public record Line(String text, double start, double end) {
    }

    /** textY 가 이 값이면 아래 레터박스 가운데에 놓는다. */
    public static final double AUTO = -1;

    /** 장면들의 길이 합 (암전 제외). */
    public double scenesDuration() {
        double sum = 0;
        for (Scene s : scenes) sum += s.duration();
        return sum;
    }

    /** 암전까지 포함한 전체 길이. */
    public double totalDuration() {
        return scenesDuration() + endFade;
    }
}

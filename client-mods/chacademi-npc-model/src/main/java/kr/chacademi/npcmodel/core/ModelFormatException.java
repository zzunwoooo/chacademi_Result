package kr.chacademi.npcmodel.core;

/** 모델·설정 파일을 거부할 때. 메시지는 사람이 읽는 한국어 진단. */
public final class ModelFormatException extends RuntimeException {
    public ModelFormatException(String message) {
        super(message);
    }
}

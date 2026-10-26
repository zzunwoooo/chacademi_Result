package kr.chacademi.npcmodel.core;

/** 파일 하나에 허용하는 상한. 넘으면 그 모델은 거부되고 기존 NPC 외형으로 남는다. */
public final class ModelLimits {
    public static final ModelLimits DEFAULT = new ModelLimits(48L << 20, 512, 256, 32, 64, 200_000, 1_500_000, 8, 4096, 64, 4_000_000);

    public final long maxFileBytes;
    public final int maxNodes, maxJoints, maxMeshes, maxPrimitives, maxVertices, maxIndices, maxImages, maxImageSide, maxClips;
    public final long maxKeyframeFloats;

    public ModelLimits(long maxFileBytes, int maxNodes, int maxJoints, int maxMeshes, int maxPrimitives, int maxVertices,
                       int maxIndices, int maxImages, int maxImageSide, int maxClips, long maxKeyframeFloats) {
        this.maxFileBytes = maxFileBytes;
        this.maxNodes = maxNodes;
        this.maxJoints = maxJoints;
        this.maxMeshes = maxMeshes;
        this.maxPrimitives = maxPrimitives;
        this.maxVertices = maxVertices;
        this.maxIndices = maxIndices;
        this.maxImages = maxImages;
        this.maxImageSide = maxImageSide;
        this.maxClips = maxClips;
        this.maxKeyframeFloats = maxKeyframeFloats;
    }
}

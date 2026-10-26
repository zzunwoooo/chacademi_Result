package dev.portablevfx.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;

/** Dependency-free executable tests; runs with java, without JUnit or Minecraft. */
public final class ProtocolTests {
    private static final UUID ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static int assertions;

    private ProtocolTests() {
    }

    public static void main(String[] arguments) throws Exception {
        EffectOwner owner=new EffectOwner(ID,UUID.randomUUID());
        if(!owner.equals(EffectOwner.decode(owner.encode())))throw new AssertionError("owner roundtrip");
        for(byte[] bad:new byte[][]{new byte[0],new byte[32],new byte[34],new byte[33]}){
            try{EffectOwner.decode(bad);throw new AssertionError("invalid owner accepted");}catch(IllegalArgumentException expected){}
        }
        readiness();
        CatalogReadinessV2Tests.run();
        parameterizedPlay();
        finishIntent();
        widthPlay();
        authoritativeControls();
        extendedPlayAndAnchorMath();
        orientationState();
        orientationRoundTrip();
        goldenWireFormat();
        followRoundTrip();
        roundTripsAndBoundaries();
        rejectsInvalidConstructors();
        rejectsMalformedEnvelopes();
        rejectsInvalidWireValues();
        randomizedRoundTrips();
        fuzzDecoders();
        System.out.println("PortableVFX protocol tests passed (" + assertions + " assertions).");
    }

    private static void readiness() throws Exception {
        var ids=java.util.Set.of("claude:fireball/projectile","claude:fireball/impact");
        equal(ids,CatalogReadiness.decode(CatalogReadiness.encode(ids)),"ready inventory round trip");
        equal(java.util.Set.of(),CatalogReadiness.decode(CatalogReadiness.encode(java.util.Set.of())),"empty inventory withdraws ready");
        byte[] bytes=CatalogReadiness.encode(ids);
        for(int n=0;n<bytes.length;n++){final byte[] truncated=Arrays.copyOf(bytes,n);expectProtocol(()->CatalogReadiness.decode(truncated));}
        expectProtocol(()->CatalogReadiness.decode(Arrays.copyOf(bytes,bytes.length+1)));
        expectInvalid(()->CatalogReadiness.encode(java.util.List.of("claude:a/a","claude:a/a")));
        expectInvalid(()->CatalogReadiness.encode(java.util.Set.of("other:a")));
    }

    private static void parameterizedPlay() throws Exception {
        PlayEffect p=new PlayEffect(ID,"claude:link/main","m:o",0,0,0,0,0,0,1,0xffffff,1,2400).withParameters(2,12);
        roundTrip(p);equal(12,(int)VfxProtocol.encode(p)[4],"parameterized play opcode");
        roundTrip(p.withEffectWidth(4).withStartTick(5));
        equal(2d,p.withStartTick(2).scaleInput(),"scale survives synchronization");
        roundTrip(new PoseEffect(ID,"m:o",2,1,2,3,EffectBasis.identity(),12));
        roundTrip(new ImpactEffect(new UUID(2,3),4,p,EffectBasis.identity()));
        expectInvalid(()->p.withParameters(Double.NaN,1));expectInvalid(()->p.withParameters(1,257));expectInvalid(()->p.withParameters(1,-.5));
        expectInvalid(()->new PoseEffect(ID,"m:o",2,0,0,0,EffectBasis.identity(),-.5));
    }

    private static void finishIntent() throws Exception {
        var normal=new FinishEffect(ID,"m:o",9,false);roundTrip(normal);
        byte[] bytes=VfxProtocol.encode(normal);equal(11,(int)bytes[4],"explicit finish intent opcode");
        equal(true,VfxProtocol.isPriorityControl(bytes),"finish intent retains priority");
        byte[] invalid=bytes.clone();invalid[invalid.length-1]=1;expectProtocol(()->VfxProtocol.decode(invalid));
        invalid[invalid.length-1]=2;expectProtocol(()->VfxProtocol.decode(invalid));
        equal(8,(int)VfxProtocol.encode(new FinishEffect(ID,"m:o",9))[4],"legacy clear-local finish unchanged");
    }

    private static void widthPlay() throws Exception {
        equal("portablevfx:width_hello", VfxProtocol.WIDTH_PLAY_HELLO_CHANNEL, "independent width channel");
        PlayEffect authored = new PlayEffect(ID, "claude:tidalwave/wave", "m:o", 1,2,3,0,0,0,1,0xffffff,1,200);
        byte[] legacy = VfxProtocol.encode(authored);
        equal(1, (int) legacy[4], "authored width keeps legacy opcode");
        for (double width : new double[]{Double.MIN_VALUE, 3.5, 7, 15.4, 64}) {
            PlayEffect wide = authored.withEffectWidth(width);
            equal(width, wide.effectWidth(), "width independent from scale"); equal(1f, wide.scale(), "uniform scale remains 1");
            equal(width, wide.withStartTick(99).effectWidth(), "world-time synchronization preserves width");
            roundTrip(wide);
            equal(VfxProtocol.OP_WIDTH_PLAY, (int) VfxProtocol.encode(wide)[4], "atomic width PLAY opcode");
            try { wide.legacy(); throw new AssertionError("Width downgrade must fail"); } catch (IllegalStateException expected) { assertions++; }
            ImpactEffect collapse = new ImpactEffect(new UUID(1,2), 2, wide, EffectBasis.identity());
            roundTrip(collapse);
            byte[] bytes = VfxProtocol.encode(wide);
            for (int n=0;n<bytes.length;n++) {
                byte[] prefix=Arrays.copyOf(bytes,n); expectProtocol(()->VfxProtocol.decode(prefix));
            }
            for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,64.01}) {
                byte[] mutation=bytes.clone();ByteBuffer.wrap(mutation).putDouble(bytes.length-8,invalid);
                expectProtocol(()->VfxProtocol.decode(mutation));
            }
            expectProtocol(()->VfxProtocol.decode(Arrays.copyOf(bytes,bytes.length+1)));
        }
        equal(true, Arrays.equals(legacy,VfxProtocol.encode(authored.withEffectWidth(0))), "default preserves exact legacy bytes");
        for(double invalid:new double[]{-0.0,-1,Double.NaN,Double.POSITIVE_INFINITY,64.01})
            expectInvalid(()->authored.withEffectWidth(invalid));
        expectInvalid(()->fixture().build().withEffectWidth(7));
    }

    private static void authoritativeControls() throws Exception {
        equal("portablevfx:authority_hello", VfxProtocol.AUTHORITATIVE_HELLO_CHANNEL, "independent authority channel");
        EffectBasis basis=EffectBasis.identity();
        PlayEffect phase=new PlayEffect(new UUID(0,42),"p:i","m:o",1,2,3,0,0,0,1,0xffffff,1,100).withStartTick(9);
        PoseEffect pose=new PoseEffect(ID,"m:o",7,1,2,3,basis);
        FinishEffect finish=new FinishEffect(ID,"m:o",8);
        ImpactEffect impact=new ImpactEffect(ID,8,phase,EffectBasis.impact(0,1,0,0,-1,0));
        for (EffectMessage message:new EffectMessage[]{pose,finish,impact}) {
            roundTrip(message);
            byte[] bytes=VfxProtocol.encode(message);
            for(int n=0;n<bytes.length;n++) {
                byte[] prefix=Arrays.copyOf(bytes,n);
                expectProtocol(()->VfxProtocol.decode(prefix));
            }
            expectProtocol(()->VfxProtocol.decode(Arrays.copyOf(bytes,bytes.length+1)));
            Random random=new Random(bytes[4]);
            for(int n=0;n<1000;n++) {
                byte[] mutation=bytes.clone(); int at=random.nextInt(mutation.length);
                mutation[at]^=(byte)(1<<random.nextInt(8)); decodeOrReject(mutation);
            }
        }
        equal(94,VfxProtocol.encode(pose).length,"minimum pose size");
        equal(34,VfxProtocol.encode(finish).length,"minimum finish size");
        equal(179,VfxProtocol.encode(impact).length,"impact includes bounded full PLAY plus basis");
        byte[] poseBytes=VfxProtocol.encode(pose);
        equal(7, (int)poseBytes[4],"pose opcode");
        equal(7L,ByteBuffer.wrap(poseBytes).getLong(26),"pose sequence field");
        equal(1d,ByteBuffer.wrap(poseBytes).getDouble(34),"pose position field");
        equal(1f,ByteBuffer.wrap(poseBytes).getFloat(58),"pose basis field");
        equal(true,VfxProtocol.isPriorityControl(VfxProtocol.encode(finish)),"finish control priority");
        equal(true,VfxProtocol.isPriorityControl(VfxProtocol.encode(new StopEffect(ID))),"legacy stop priority");
        equal(true,VfxProtocol.isPriorityControl(VfxProtocol.encode(new ClearEffects())),"legacy clear priority");
        equal(false,VfxProtocol.isPriorityControl(poseBytes),"poses use ordinary budget");
        equal(false,VfxProtocol.isPriorityControl(VfxProtocol.encode(impact)),"impact uses ordinary budget");
        for(int size=0;size<VfxProtocol.encode(finish).length;size++)
            equal(false,VfxProtocol.isPriorityControl(Arrays.copyOf(VfxProtocol.encode(finish),size)),"truncated finish no priority");
        byte[] badFinish=VfxProtocol.encode(finish); ByteBuffer.wrap(badFinish).putLong(26,-1);
        equal(false,VfxProtocol.isPriorityControl(badFinish),"invalid finish no priority");
        expectInvalid(()->new PoseEffect(ID,"m:o",-1,0,0,0,basis));
        expectInvalid(()->new FinishEffect(ID,"m:o",-1));
        expectInvalid(()->new ImpactEffect(ID,-1,phase,basis));
        expectInvalid(()->new ImpactEffect(ID,1,fixture().build(),basis));
        expectInvalid(()->new ImpactEffect(ID,1,null,basis));
        expectInvalid(()->new PoseEffect(ID,"m:o",0,0,0,0,null));
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,30_000_001})
            expectInvalid(()->new PoseEffect(ID,"m:o",0,value,0,0,basis));
        for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,2,0})
            expectInvalid(()->new EffectBasis(value,0,0,0,1,0,0,0,1));
        expectInvalid(()->new EffectBasis(-1,0,0,0,1,0,0,0,1));
        expectInvalid(()->EffectBasis.projectile(0,0,0));
        expectInvalid(()->EffectBasis.projectile(Double.NaN,0,0));
        equal(EffectBasis.identity(),EffectBasis.projectile(0,0,1),"positive-Z identity frame");
        equal(1f,EffectBasis.projectile(0,1,0).forwardY(),"vertical basis finite");
        equal(1f,EffectBasis.impact(0,1,0,0,-1,0).upY(),"head-on ground hit fallback");
        equal(1f,EffectBasis.impact(1,0,0,-1,0,0).upX(),"head-on wall hit fallback");
        EffectBasis.projectile(Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE);
        float[] copy=basis.toArray();copy[0]=0;equal(1f,basis.rightX(),"basis array defensive copy");
        byte[] badImpact=VfxProtocol.encode(impact);badImpact[35]=(byte)VfxProtocol.OP_IMPACT;
        expectProtocol(()->VfxProtocol.decode(badImpact));
        byte[] oversizedNested=VfxProtocol.encode(impact);ByteBuffer.wrap(oversizedNested).putShort(29,(short)2048);
        expectProtocol(()->VfxProtocol.decode(oversizedNested));
        EffectControlState state=new EffectControlState(ID,"m:o");
        equal(true,state.accept(ID,"m:o",0,false),"first pose");
        equal(false,state.accept(ID,"m:o",0,false),"duplicate pose");
        equal(false,state.accept(ID,"m:o",-1,false),"negative sequence");
        equal(false,state.accept(new UUID(0,0),"m:o",1,false),"foreign UUID");
        equal(false,state.accept(ID,"m:x",1,false),"foreign dimension");
        equal(true,state.accept(ID,"m:o",5,false),"sequence skips allowed");
        equal(false,state.accept(ID,"m:o",4,true),"stale finish");
        equal(true,state.accept(ID,"m:o",6,true),"terminal phase accepted");
        equal(false,state.accept(ID,"m:o",7,false),"no pose after finish");
        equal(false,state.accept(ID,"m:o",7,true),"no duplicate impact after finish");
        equal(6L,state.sequence(),"terminal sequence stable");
        EffectControlState fresh=new EffectControlState(ID,"m:o");
        equal(true,fresh.accept(ID,"m:o",Long.MAX_VALUE,true),"fresh lifetime max sequence");
        equal(false,fresh.accept(ID,"m:o",0,false),"sequence never wraps");
        Random random=new Random(901);
        for(int n=0;n<1000;n++) {
            EffectBasis generated=EffectBasis.projectile(random.nextDouble()-.5,random.nextDouble()-.5,random.nextDouble()-.5);
            roundTrip(new PoseEffect(ID,"m:o",n,1,2,3,generated));
        }
    }

    private static PlayEffect extended(EffectAnchor anchor, float x, float y, float z, long seed, long tick) {
        return new PlayEffect(ID, "p:e", "m:o", 1, 2, 3, 4, 5, 6, 7, 0x123456, .5f, 20,
                anchor == EffectAnchor.WORLD ? null : new UUID(17, 29), seed, tick, anchor, x, y, z);
    }

    private static void extendedPlayAndAnchorMath() throws Exception {
        equal(fixture().build().seed(), PlayEffect.defaultSeed(ID), "legacy stable UUID seed");
        equal(-1L, fixture().build().startTick(), "legacy immediate start");
        equal(EffectAnchor.WORLD, fixture().build().anchor(), "legacy world placement");
        equal(false, fixture().build().extended(), "legacy constructors keep old opcode");
        for (EffectAnchor anchor : EffectAnchor.values()) {
            equal(anchor, EffectAnchor.parse(anchor.id()), "named anchor parse");
            PlayEffect play = extended(anchor, -64, 64, -.25f, 0, 12345);
            byte[] bytes = VfxProtocol.encode(play);
            equal(6, (int) bytes[4], "extended opcode");
            equal(anchor == EffectAnchor.WORLD ? 112 : 128, bytes.length, "bounded extension size");
            equal(0L, ByteBuffer.wrap(bytes).getLong(83), "seed field order");
            equal(12345L, ByteBuffer.wrap(bytes).getLong(91), "tick field order");
            equal(anchor.wireId(), (int) bytes[99], "anchor field order");
            roundTrip(play);
            for (int n = 0; n < bytes.length; n++) {
                byte[] prefix = Arrays.copyOf(bytes, n);
                expectProtocol(() -> VfxProtocol.decode(prefix));
            }
            expectProtocol(() -> VfxProtocol.decode(Arrays.copyOf(bytes, bytes.length + 1)));
            byte[] badAnchor = bytes.clone(); badAnchor[99] = 5;
            expectProtocol(() -> VfxProtocol.decode(badAnchor));
            byte[] badTick = bytes.clone(); ByteBuffer.wrap(badTick).putLong(91, -2);
            expectProtocol(() -> VfxProtocol.decode(badTick));
            for (int position : new int[] {100, 104, 108}) {
                for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, 64.01f, -64.01f}) {
                    byte[] badOffset = bytes.clone(); ByteBuffer.wrap(badOffset).putFloat(position, invalid);
                    expectProtocol(() -> VfxProtocol.decode(badOffset));
                }
            }
        }
        expectInvalid(() -> EffectAnchor.parse("HEAD"));
        expectInvalid(() -> EffectAnchor.fromWireId(255));
        expectInvalid(() -> extended(null, 0, 0, 0, 0, -1));
        expectInvalid(() -> extended(EffectAnchor.WORLD, 0, 0, 0, 0, -2));
        expectInvalid(() -> extended(EffectAnchor.HEAD, Float.NaN, 0, 0, 0, -1));
        expectInvalid(() -> new PlayEffect(ID, "p:e", "m:o", 0,0,0,0,0,0,1,0xffffff,1,20,
                null, 0, -1, EffectAnchor.HEAD, 0,0,0));
        expectInvalid(() -> new PlayEffect(ID, "p:e", "m:o", 0,0,0,0,0,0,1,0xffffff,1,20,
                ID, 0, -1, EffectAnchor.WORLD, 0,0,0));
        // A redundant extension would violate canonical byte round trips and is rejected.
        byte[] redundant = VfxProtocol.encode(extended(EffectAnchor.WORLD, 0,0,0,0,-1));
        ByteBuffer.wrap(redundant).putLong(83, PlayEffect.defaultSeed(ID));
        expectProtocol(() -> VfxProtocol.decode(redundant));
        for (long seed : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, -1, 0, 1})
            for (long tick : new long[] {-1, 0, Long.MAX_VALUE})
                roundTrip(extended(EffectAnchor.WORLD, -0f,0,0,seed,tick));
        double[] turn = AnchorTransform.rotate(0, 0, 1, 90, 0, 0);
        check(Math.abs(turn[0] + 1) < 1e-9 && Math.abs(turn[2]) < 1e-9, "Minecraft forward yaw");
        double[] roll = AnchorTransform.rotate(1, 0, 0, 0, 0, 90);
        check(Math.abs(roll[1] - 1) < 1e-9, "local roll");
        double[] combined = AnchorTransform.rotate(1,2,3,90,90,0);
        check(Math.abs(combined[0]+3)<1e-9 && Math.abs(combined[1]+1)<1e-9 && Math.abs(combined[2]-2)<1e-9,
                "combined Euler order matches renderer rotateXYZ(pitch,-yaw,roll)");
        expectInvalid(() -> AnchorTransform.rotate(0,0,0,Double.NaN,0,0));
        Random random = new Random(72);
        byte[] seedPacket = VfxProtocol.encode(extended(EffectAnchor.HEAD, 1,2,3,42,100));
        for (int i = 0; i < 2000; i++) {
            byte[] mutation = seedPacket.clone();
            int index = random.nextInt(mutation.length);
            mutation[index] ^= (byte) (1 << random.nextInt(8));
            decodeOrReject(mutation);
        }
    }

    private static void orientationState() {
        var initial=new PlayEffect(ID,"portablevfx:demo","minecraft:overworld",0,0,0,17,23,31,1,0xffffff,1,100);
        var state=new EffectOrientation(initial);
        equal(17f,state.yaw(),"initial yaw retained");equal(23f,state.pitch(),"initial pitch retained");
        equal(true,state.accept(new OrientEffect(ID,1,90,0,20)),"fresh update");
        equal(false,state.accept(new OrientEffect(ID,1,0,0,0)),"duplicate update");
        equal(false,state.accept(new OrientEffect(ID,0,0,0,0)),"stale update");
        equal(false,state.accept(new OrientEffect(UUID.randomUUID(),2,0,0,0)),"foreign update");
        equal(90f,state.yaw(),"no stale override");
        equal(90f,EffectOrientation.towardPositiveX(ID,2,0,0,1).yaw(),"positive Z yaw");
        equal(90f,EffectOrientation.towardPositiveX(ID,2,0,1,0).roll(),"positive Y roll");
        equal(0f,EffectOrientation.towardPositiveX(ID,2,1,0,0).yaw(),"positive X identity");
    }

    private static void orientationRoundTrip() throws Exception {
        OrientEffect value=new OrientEffect(ID,19,-360,45,360);
        byte[] encoded=VfxProtocol.encode(value);
        equal(41,encoded.length,"orientation exact bound");
        equal(value,VfxProtocol.decode(encoded),"orientation roundtrip");
        for(int n=0;n<encoded.length;n++){byte[] p=Arrays.copyOf(encoded,n);expectProtocol(()->VfxProtocol.decode(p));}
        expectProtocol(()->VfxProtocol.decode(Arrays.copyOf(encoded,encoded.length+1)));
        for(float angle:new float[]{Float.NaN,Float.POSITIVE_INFINITY,361,-361}){
            boolean rejected=false;try{new OrientEffect(ID,0,angle,0,0);}catch(IllegalArgumentException e){rejected=true;}
            equal(true,rejected,"orientation finite/bounded");
        }
        boolean rejected=false;try{new OrientEffect(ID,-1,0,0,0);}catch(IllegalArgumentException e){rejected=true;}
        equal(true,rejected,"negative sequence");
    }

    private static void followRoundTrip() throws Exception {
        PlayEffect follow = new PlayEffect(ID, "magiccodex:feather_fall", "minecraft:overworld",
                3, 4, 5, 0, 0, 0, 1, 0xffffff, 1, 100, new UUID(17, 29));
        byte[] bytes = VfxProtocol.encode(follow);
        equal(4, (int)bytes[4], "follow uses distinct opcode");
        equal(follow, VfxProtocol.decode(bytes), "follow UUID roundtrip");
        for (int n = bytes.length - 16; n < bytes.length; n++) {
            byte[] truncated = Arrays.copyOf(bytes, n);
            expectProtocol(() -> VfxProtocol.decode(truncated));
        }
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        expectProtocol(() -> VfxProtocol.decode(trailing));
    }

    private static void goldenWireFormat() throws Exception {
        bytesEqual(hex("00000001"), VfxProtocol.encodeHello(), "hello golden bytes");
        equal(1, VfxProtocol.decodeHello(hex("00000001")), "hello version");
        bytesEqual(hex("0000000103"), VfxProtocol.encode(new ClearEffects()), "clear golden bytes");
        bytesEqual(hex("000000010200112233445566778899aabbccddeeff"),
                VfxProtocol.encode(new StopEffect(ID)), "stop golden bytes");

        byte[] golden = hex("00000001 01 0011223344556677 8899aabbccddeeff"
                + " 0003 703a65 0003 6d3a6f"
                + " 3ff0000000000000 4000000000000000 4008000000000000"
                + " 40800000 40a00000 40c00000 40e00000 00123456 3f000000 00000014");
        PlayEffect play = fixture().build();
        bytesEqual(golden, VfxProtocol.encode(play), "play golden bytes and field order");
        equal(play, VfxProtocol.decode(golden), "play golden decode");
        equal(83, golden.length, "small play wire size");
    }

    private static void roundTripsAndBoundaries() throws Exception {
        roundTrip(fixture().build());
        roundTrip(new StopEffect(ID));
        roundTrip(new ClearEffects());

        Fixture limits = fixture();
        limits.effectId = "a:" + "b".repeat(126);
        limits.dimensionId = "c:" + "d".repeat(126);
        limits.x = -VfxProtocol.MAX_POSITION;
        limits.y = VfxProtocol.MAX_POSITION;
        limits.z = -0.0d;
        limits.yaw = -Float.MAX_VALUE;
        limits.pitch = Float.MAX_VALUE;
        limits.roll = -0.0f;
        limits.scale = VfxProtocol.MAX_SCALE;
        limits.rgb = 0xFFFFFF;
        limits.opacity = 1.0f;
        limits.duration = VfxProtocol.MAX_DURATION_TICKS;
        roundTrip(limits.build());
        equal(333, VfxProtocol.encode(limits.build()).length, "max v1 message wire size");

        limits.scale = Float.MIN_VALUE;
        limits.opacity = 0.0f;
        limits.rgb = 0;
        limits.duration = 1;
        roundTrip(limits.build());
        equal("portablevfx:demo/pulse-ring_1.0", VfxProtocol.validateId("portablevfx:demo/pulse-ring_1.0"),
                "valid identifier helper");

        byte[] encoded = VfxProtocol.encode(fixture().build());
        byte[] before = encoded.clone();
        VfxProtocol.decode(encoded);
        bytesEqual(before, encoded, "decode must not mutate caller's bytes");
        byte[] hello = VfxProtocol.encodeHello();
        hello[3] = 2;
        equal(1, VfxProtocol.decodeHello(VfxProtocol.encodeHello()), "hello result has no shared mutable backing");
    }

    private static void rejectsInvalidConstructors() {
        expectInvalid(() -> VfxProtocol.encode(null));
        expectInvalid(() -> new StopEffect(null));
        Fixture bad = fixture();
        bad.id = null;
        expectInvalid(bad::build);

        String[] invalidIds = { null, "", "abc", ":path", "ns:", "A:b", "a:B", "a:b:c", "a:b c",
                "a:b\\c", "a:\u00e9", "a:\u0000", "a:" + "b".repeat(127), "a:/b", "a:b/", "a:b//c",
                "a:.", "a:..", "a:../b", "a:b/./c", "a:b/../c", ".:b", "..:b" };
        for (String id : invalidIds) {
            expectInvalid(() -> VfxProtocol.validateId(id));
            Fixture badEffect = fixture();
            badEffect.effectId = id;
            expectInvalid(badEffect::build);
            Fixture badDimension = fixture();
            badDimension.dimensionId = id;
            expectInvalid(badDimension::build);
        }

        double[] invalidPositions = { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Math.nextUp(VfxProtocol.MAX_POSITION), Math.nextDown(-VfxProtocol.MAX_POSITION) };
        for (double value : invalidPositions) {
            for (int coordinate = 0; coordinate < 3; coordinate++) {
                Fixture invalid = fixture();
                if (coordinate == 0) invalid.x = value;
                if (coordinate == 1) invalid.y = value;
                if (coordinate == 2) invalid.z = value;
                expectInvalid(invalid::build);
            }
        }
        float[] nonFinite = { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY };
        for (float value : nonFinite) {
            for (int field = 0; field < 5; field++) {
                Fixture invalid = fixture();
                if (field == 0) invalid.yaw = value;
                if (field == 1) invalid.pitch = value;
                if (field == 2) invalid.roll = value;
                if (field == 3) invalid.scale = value;
                if (field == 4) invalid.opacity = value;
                expectInvalid(invalid::build);
            }
        }
        for (float value : new float[] { 0f, -0f, -Float.MIN_VALUE, Math.nextUp(VfxProtocol.MAX_SCALE) }) {
            Fixture invalid = fixture();
            invalid.scale = value;
            expectInvalid(invalid::build);
        }
        for (float value : new float[] { -Float.MIN_VALUE, Math.nextUp(1f) }) {
            Fixture invalid = fixture();
            invalid.opacity = value;
            expectInvalid(invalid::build);
        }
        for (int value : new int[] { -1, 0x1000000, Integer.MAX_VALUE }) {
            Fixture invalid = fixture();
            invalid.rgb = value;
            expectInvalid(invalid::build);
        }
        for (int value : new int[] { Integer.MIN_VALUE, 0, VfxProtocol.MAX_DURATION_TICKS + 1, Integer.MAX_VALUE }) {
            Fixture invalid = fixture();
            invalid.duration = value;
            expectInvalid(invalid::build);
        }
    }

    private static void rejectsMalformedEnvelopes() throws Exception {
        expectProtocol(() -> VfxProtocol.decode(null));
        expectProtocol(() -> VfxProtocol.decodeHello(null));
        expectProtocol(() -> VfxProtocol.decode(new byte[0]));
        expectProtocol(() -> VfxProtocol.decodeHello(new byte[0]));
        expectProtocol(() -> VfxProtocol.decode(new byte[VfxProtocol.MAX_PACKET_BYTES + 1]));
        expectProtocol(() -> VfxProtocol.decodeHello(new byte[VfxProtocol.MAX_PACKET_BYTES + 1]));

        for (EffectMessage message : new EffectMessage[] { fixture().build(), new StopEffect(ID), new ClearEffects() }) {
            byte[] valid = VfxProtocol.encode(message);
            for (int size = 0; size < valid.length; size++) {
                byte[] truncated = Arrays.copyOf(valid, size);
                expectProtocol(() -> VfxProtocol.decode(truncated));
            }
            expectProtocol(() -> VfxProtocol.decode(Arrays.copyOf(valid, valid.length + 1)));
            expectProtocol(() -> VfxProtocol.decode(Arrays.copyOf(valid, VfxProtocol.MAX_PACKET_BYTES)));
            expectProtocol(() -> VfxProtocol.decodeHello(valid));
        }
        byte[] hello = VfxProtocol.encodeHello();
        for (int size = 0; size < hello.length; size++) {
            byte[] truncated = Arrays.copyOf(hello, size);
            expectProtocol(() -> VfxProtocol.decodeHello(truncated));
        }
        expectProtocol(() -> VfxProtocol.decode(hello));
        expectProtocol(() -> VfxProtocol.decodeHello(Arrays.copyOf(hello, 5)));

        for (int version : new int[] { Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE }) {
            byte[] badHello = ByteBuffer.allocate(4).putInt(version).array();
            expectProtocol(() -> VfxProtocol.decodeHello(badHello));
            byte[] badEffect = VfxProtocol.encode(new ClearEffects());
            ByteBuffer.wrap(badEffect).putInt(version);
            expectProtocol(() -> VfxProtocol.decode(badEffect));
        }
        for (int opcode = 0; opcode <= 255; opcode++) {
            if (opcode >= 1 && opcode <= 3) continue;
            byte[] unknown = VfxProtocol.encode(new ClearEffects());
            unknown[4] = (byte) opcode;
            expectProtocol(() -> VfxProtocol.decode(unknown));
        }
    }

    private static void rejectsInvalidWireValues() throws Exception {
        byte[] valid = VfxProtocol.encode(fixture().build());
        for (int offset : new int[] { 21, 26 }) {
            for (int length : new int[] { 0, 1, 2, 129, 0xFFFF }) {
                byte[] altered = valid.clone();
                ByteBuffer.wrap(altered).putShort(offset, (short) length);
                expectProtocol(() -> VfxProtocol.decode(altered));
            }
        }
        for (int offset : new int[] { 23, 28 }) {
            for (int value : new int[] { 0, 'A', ' ', 0x80, 0xFF }) {
                byte[] altered = valid.clone();
                altered[offset] = (byte) value;
                expectProtocol(() -> VfxProtocol.decode(altered));
            }
        }
        for (int offset : new int[] { 31, 39, 47 }) {
            for (double value : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Math.nextUp(VfxProtocol.MAX_POSITION), Math.nextDown(-VfxProtocol.MAX_POSITION) }) {
                byte[] altered = valid.clone();
                ByteBuffer.wrap(altered).putDouble(offset, value);
                expectProtocol(() -> VfxProtocol.decode(altered));
            }
        }
        for (int offset : new int[] { 55, 59, 63, 67, 75 }) {
            for (float value : new float[] { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY }) {
                byte[] altered = valid.clone();
                ByteBuffer.wrap(altered).putFloat(offset, value);
                expectProtocol(() -> VfxProtocol.decode(altered));
            }
        }
        for (float value : new float[] { 0f, -0f, -1f, Math.nextUp(VfxProtocol.MAX_SCALE) }) {
            byte[] altered = valid.clone();
            ByteBuffer.wrap(altered).putFloat(67, value);
            expectProtocol(() -> VfxProtocol.decode(altered));
        }
        for (int value : new int[] { -1, 0x1000000 }) {
            byte[] altered = valid.clone();
            ByteBuffer.wrap(altered).putInt(71, value);
            expectProtocol(() -> VfxProtocol.decode(altered));
        }
        for (float value : new float[] { -Float.MIN_VALUE, Math.nextUp(1f) }) {
            byte[] altered = valid.clone();
            ByteBuffer.wrap(altered).putFloat(75, value);
            expectProtocol(() -> VfxProtocol.decode(altered));
        }
        for (int value : new int[] { 0, VfxProtocol.MAX_DURATION_TICKS + 1, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            byte[] altered = valid.clone();
            ByteBuffer.wrap(altered).putInt(79, value);
            expectProtocol(() -> VfxProtocol.decode(altered));
        }
    }

    private static void randomizedRoundTrips() throws Exception {
        Random random = new Random(0x504F525441424C45L);
        for (int i = 0; i < 2000; i++) {
            roundTrip(new PlayEffect(new UUID(random.nextLong(), random.nextLong()),
                    "portablevfx:test/" + i, "minecraft:overworld",
                    (random.nextDouble() * 2 - 1) * VfxProtocol.MAX_POSITION,
                    (random.nextDouble() * 2 - 1) * VfxProtocol.MAX_POSITION,
                    (random.nextDouble() * 2 - 1) * VfxProtocol.MAX_POSITION,
                    random.nextFloat() * 720 - 360, random.nextFloat() * 720 - 360,
                    random.nextFloat() * 720 - 360, (random.nextFloat() + 1) * 16,
                    random.nextInt(0x1000000), random.nextFloat(),
                    random.nextInt(VfxProtocol.MAX_DURATION_TICKS) + 1));
        }
    }

    private static void fuzzDecoders() throws Exception {
        Random random = new Random(0x564658);
        for (int i = 0; i < 5000; i++) {
            byte[] arbitrary = new byte[random.nextInt(3073)];
            random.nextBytes(arbitrary);
            decodeOrReject(arbitrary);
            try {
                equal(VfxProtocol.VERSION, VfxProtocol.decodeHello(arbitrary), "fuzz hello version");
                bytesEqual(VfxProtocol.encodeHello(), arbitrary, "fuzz hello canonical bytes");
            } catch (ProtocolException expected) {
                assertions++;
            }
        }
        byte[] seed = VfxProtocol.encode(fixture().build());
        for (int i = 0; i < 5000; i++) {
            byte[] mutated = seed.clone();
            int mutations = random.nextInt(5) + 1;
            for (int j = 0; j < mutations; j++) {
                int index = random.nextInt(mutated.length);
                mutated[index] ^= (byte) (1 << random.nextInt(8));
            }
            decodeOrReject(mutated);
        }
    }

    private static void decodeOrReject(byte[] payload) throws Exception {
        try {
            EffectMessage message = VfxProtocol.decode(payload);
            bytesEqual(payload, VfxProtocol.encode(message), "accepted fuzz payload must round-trip canonically");
        } catch (ProtocolException expected) {
            assertions++;
        }
    }

    private static void roundTrip(EffectMessage message) throws Exception {
        byte[] bytes = VfxProtocol.encode(message);
        check(bytes.length <= VfxProtocol.MAX_PACKET_BYTES, "encoded packet size is bounded");
        equal(message, VfxProtocol.decode(bytes), "message round-trip");
        bytesEqual(bytes, VfxProtocol.encode(VfxProtocol.decode(bytes)), "canonical re-encoding");
    }

    private static byte[] hex(String text) {
        return HexFormat.of().parseHex(text.replace(" ", ""));
    }

    private static Fixture fixture() {
        return new Fixture();
    }

    private static void expectProtocol(CheckedAction action) {
        try {
            action.run();
        } catch (ProtocolException expected) {
            assertions++;
            return;
        } catch (Exception exception) {
            throw new AssertionError("Expected ProtocolException, got " + exception, exception);
        }
        throw new AssertionError("Expected ProtocolException, but payload was accepted");
    }

    private static void expectInvalid(CheckedAction action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            assertions++;
            return;
        } catch (Exception exception) {
            throw new AssertionError("Expected IllegalArgumentException, got " + exception, exception);
        }
        throw new AssertionError("Expected IllegalArgumentException, but value was accepted");
    }

    private static void bytesEqual(byte[] expected, byte[] actual, String label) {
        check(Arrays.equals(expected, actual), label);
    }

    private static void equal(Object expected, Object actual, String label) {
        check(expected.equals(actual), label + ": expected=" + expected + ", actual=" + actual);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++;
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class Fixture {
        private UUID id = ID;
        private String effectId = "p:e";
        private String dimensionId = "m:o";
        private double x = 1;
        private double y = 2;
        private double z = 3;
        private float yaw = 4;
        private float pitch = 5;
        private float roll = 6;
        private float scale = 7;
        private int rgb = 0x123456;
        private float opacity = 0.5f;
        private int duration = 20;

        private PlayEffect build() {
            return new PlayEffect(id, effectId, dimensionId, x, y, z, yaw, pitch, roll,
                    scale, rgb, opacity, duration);
        }
    }
}

package dev.portablevfx.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Client reports only fully uploaded, prewarmed effect IDs; an empty set withdraws readiness. */
public final class CatalogReadiness {
    public static final String CHANNEL="portablevfx:catalog_ready";
    public static final int MAX_BYTES=32760, MAX_EFFECTS=1024;
    private CatalogReadiness() { }
    public static byte[] encode(Collection<String> ids) {
        TreeSet<String> sorted=new TreeSet<>(ids);if(sorted.size()!=ids.size()||sorted.size()>MAX_EFFECTS)throw new IllegalArgumentException("Invalid ready effect count");
        try {var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(VfxProtocol.VERSION);out.writeShort(sorted.size());
            for(String id:sorted){VfxProtocol.validateId(id);if(!id.startsWith("claude:"))throw new IllegalArgumentException("Ready effects must be Claude IDs");byte[] raw=id.getBytes(StandardCharsets.US_ASCII);out.writeByte(raw.length);out.write(raw);}
            out.flush();if(bytes.size()>MAX_BYTES)throw new IllegalArgumentException("Ready catalogue exceeds wire budget");return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
    public static Set<String> decode(byte[] bytes) throws ProtocolException {
        if(bytes==null||bytes.length<6||bytes.length>MAX_BYTES)throw new ProtocolException("Invalid readiness size");
        try {var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=VfxProtocol.VERSION)throw new ProtocolException("Unsupported readiness version");
            int count=in.readUnsignedShort();if(count>MAX_EFFECTS)throw new ProtocolException("Too many ready effects");
            TreeSet<String> ids=new TreeSet<>();String previous="";
            for(int i=0;i<count;i++){int length=in.readUnsignedByte();if(length<1||length>VfxProtocol.MAX_ID_BYTES)throw new ProtocolException("Invalid readiness ID size");byte[] raw=new byte[length];in.readFully(raw);for(byte b:raw)if(b<0)throw new ProtocolException("Non-ASCII readiness ID");String id=new String(raw,StandardCharsets.US_ASCII);VfxProtocol.validateId(id);if(!id.startsWith("claude:")||id.compareTo(previous)<=0)throw new ProtocolException("Readiness IDs must be sorted unique Claude IDs");ids.add(id);previous=id;}
            if(in.available()!=0)throw new ProtocolException("Trailing readiness data");return Set.copyOf(ids);
        }catch(IOException|IllegalArgumentException error){if(error instanceof ProtocolException p)throw p;throw new ProtocolException("Malformed readiness payload",error);}
    }
}

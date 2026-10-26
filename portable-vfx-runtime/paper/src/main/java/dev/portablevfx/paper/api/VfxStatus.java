package dev.portablevfx.paper.api;

/** Read-only snapshot. A compatible client may not yet advertise the effect channel. */
public record VfxStatus(int protocolVersion, int compatibleClients, int receivingClients,
                        int activeHandles, int pendingControlRecipients,
                        int packetsThisTick, int playsThisTick,
                        long packetsSent, long rejectedHellos) {}

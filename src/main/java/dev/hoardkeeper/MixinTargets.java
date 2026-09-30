package dev.hoardkeeper;

/**
 * Compile-time constants for {@code @At(target = ...)} descriptors.
 *
 * <p>Annotation values must be compile-time constants, and a field carrying {@code @Unique}
 * inside a mixin class cannot be referenced from an annotation on that same class. Hence this
 * plain holder class, which is deliberately NOT a mixin.
 */
public final class MixinTargets {
    private MixinTargets() {
    }

    /**
     * {@code PacketUtils.ensureRunningOnSameThread(Packet, PacketListener, PacketProcessor)}.
     *
     * <p>Verified against the unobfuscated MC 26.2 jar: this is the first instruction of every
     * {@code ClientPacketListener.handleX} method.
     */
    public static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
                    + "Lnet/minecraft/network/protocol/Packet;"
                    + "Lnet/minecraft/network/PacketListener;"
                    + "Lnet/minecraft/network/PacketProcessor;)V";
}

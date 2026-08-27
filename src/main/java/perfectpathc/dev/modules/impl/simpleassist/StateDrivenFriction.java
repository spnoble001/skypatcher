package perfectpathc.dev.modules.impl.simpleassist;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Synchronizes a single server velocity frame with a packet-only sneak state.
 *
 * <p>The Netty hook observes S12 frames but never changes them or any local
 * movement field.  The observed frame is consumed on the client tick: START
 * queues START_SNEAKING before vanilla movement handling, and END restores the
 * state (plus sprint when it was active).  Keeping the network observation and
 * state dispatch separate is important: Netty's thread does no gameplay work.
 * </p>
 */
public final class StateDrivenFriction {
    public static final int PARTICIPANT_SIMPLE_ASSIST = 1;
    public static final int PARTICIPANT_SIMPLE_ASSIST_3D = 2;
    private static final String HANDLER_NAME = text(5, 0x5A295A055A2A5A2AL,
            0x0000000000005A3CL);
    private static final String VANILLA_PACKET_HANDLER = text(14,
            0x5A315A395A3B5A2AL, 0x5A325A055A2E5A3FL,
            0x5A365A3E5A345A3BL, 0x000000005A285A3FL);
    private static final StateDrivenFriction SHARED = new StateDrivenFriction();
    /** Entity-status opcode used by vanilla for the local hurt animation. */
    private static final byte DAMAGE_STATUS_OPCODE = 2;
    /** A velocity/status pair belongs to one network damage frame at 20 TPS. */
    private static final long DAMAGE_FRAME_WINDOW_NANOS = 125000000L;
    private static final Field NET_MANAGER = ReflectionHelper.findField(NetHandlerPlayClient.class,
            "netManager", "field_147302_e");
    private static final Field CHANNEL = ReflectionHelper.findField(NetworkManager.class,
            "channel", "field_150746_k");
    private static final Field STATUS_ENTITY_ID = ReflectionHelper.findField(S19PacketEntityStatus.class,
            "entityId", "field_149164_a");

    private final AtomicBoolean velocityFramePending = new AtomicBoolean(false);
    private final AtomicBoolean velocityFrameObserved = new AtomicBoolean(false);
    private final ChannelDuplexHandler inboundObserver = new ChannelDuplexHandler() {
        @Override public void channelRead(ChannelHandlerContext context, Object message)
                throws Exception {
            if (StateDrivenFriction.this.acceptFrames) {
                if (message instanceof S19PacketEntityStatus) {
                    StateDrivenFriction.this.observeDamageStatus((S19PacketEntityStatus) message);
                } else if (message instanceof S12PacketEntityVelocity) {
                    StateDrivenFriction.this.observeVelocityFrame((S12PacketEntityVelocity) message);
                }
            }
            super.channelRead(context, message);
        }
    };

    private volatile boolean acceptFrames;
    private volatile int localEntityId = Integer.MIN_VALUE;
    private volatile long lastDamageStatusNanos = Long.MIN_VALUE;
    private volatile long lastVelocityFrameNanos = Long.MIN_VALUE;
    private Channel attachedChannel;
    private boolean restorePending;
    private boolean restoreSprint;
    private int participants;
    private int lastStartTick = Integer.MIN_VALUE;
    private int lastEndTick = Integer.MIN_VALUE;

    public static StateDrivenFriction shared() {
        return SHARED;
    }

    /** Registers one executor; attachment happens safely at tick START. */
    public synchronized void activate(int participant) {
        this.participants |= participant;
        this.acceptFrames = true;
    }

    /**
     * Runs before vanilla's primary movement update.  It only reacts to an
     * actual S12 frame observed since the preceding client tick.
     */
    public void onTickStart(Minecraft minecraft) {
        if (minecraft == null || minecraft.thePlayer == null) return;
        EntityPlayerSP player = minecraft.thePlayer;
        if (this.participants == 0 || this.lastStartTick == player.ticksExisted) return;
        this.lastStartTick = player.ticksExisted;
        this.localEntityId = player.getEntityId();
        ensureAttached(minecraft);
        boolean damageGated = this.velocityFramePending.getAndSet(false);
        boolean velocityObserved = this.velocityFrameObserved.getAndSet(false);
        // Some server/proxy combinations defer S19 handling onto the main
        // thread.  A just-observed S12 may still be accepted when vanilla has
        // already marked the local player hurt; no unrelated S12 can arm it.
        boolean hurtTimeFallback = !damageGated && velocityObserved && player.hurtTime > 0;
        if (!(damageGated || hurtTimeFallback) || this.restorePending
                || player.sendQueue == null) return;

        this.restoreSprint = player.isSprinting();
        player.sendQueue.addToSendQueue(new C0BPacketEntityAction(player,
                C0BPacketEntityAction.Action.START_SNEAKING));
        this.restorePending = true;
    }

    /** Restores the network-only state after vanilla has emitted its movement update. */
    public void onTickEnd(Minecraft minecraft) {
        if (minecraft == null || minecraft.thePlayer == null
                || this.participants == 0
                || this.lastEndTick == minecraft.thePlayer.ticksExisted) return;
        this.lastEndTick = minecraft.thePlayer.ticksExisted;
        if (!this.restorePending
                || minecraft.thePlayer.sendQueue == null) return;
        restoreState(minecraft.thePlayer);
    }

    /** Stops observing and guarantees that a previously queued sneak state is undone. */
    public synchronized void deactivate(int participant, Minecraft minecraft) {
        this.participants &= ~participant;
        if (this.participants != 0) return;
        this.acceptFrames = false;
        this.velocityFramePending.set(false);
        this.velocityFrameObserved.set(false);
        this.localEntityId = Integer.MIN_VALUE;
        this.lastDamageStatusNanos = Long.MIN_VALUE;
        this.lastVelocityFrameNanos = Long.MIN_VALUE;
        this.lastStartTick = Integer.MIN_VALUE;
        this.lastEndTick = Integer.MIN_VALUE;
        if (this.restorePending && minecraft != null && minecraft.thePlayer != null
                && minecraft.thePlayer.sendQueue != null) {
            restoreState(minecraft.thePlayer);
        } else {
            this.restorePending = false;
            this.restoreSprint = false;
        }
        detach();
    }

    private void restoreState(EntityPlayerSP player) {
        player.sendQueue.addToSendQueue(new C0BPacketEntityAction(player,
                C0BPacketEntityAction.Action.STOP_SNEAKING));
        if (this.restoreSprint) {
            player.sendQueue.addToSendQueue(new C0BPacketEntityAction(player,
                    C0BPacketEntityAction.Action.START_SPRINTING));
        }
        this.restorePending = false;
        this.restoreSprint = false;
    }

    /** Opens the correlation window only for a local vanilla damage status. */
    private void observeDamageStatus(S19PacketEntityStatus packet) {
        if (packet.getOpCode() != DAMAGE_STATUS_OPCODE || !isLocalStatus(packet)) return;
        long now = System.nanoTime();
        this.lastDamageStatusNanos = now;
        if (isDamageVelocityPair(now, this.lastVelocityFrameNanos)) {
            this.velocityFramePending.set(true);
        }
    }

    /** Records S12 but arms the module only after damage correlation succeeds. */
    private void observeVelocityFrame(S12PacketEntityVelocity packet) {
        if (packet.getEntityID() != this.localEntityId) return;
        long now = System.nanoTime();
        this.lastVelocityFrameNanos = now;
        this.velocityFrameObserved.set(true);
        if (isDamageVelocityPair(now, this.lastDamageStatusNanos)) {
            this.velocityFramePending.set(true);
        }
    }

    private boolean isLocalStatus(S19PacketEntityStatus packet) {
        try {
            return STATUS_ENTITY_ID.getInt(packet) == this.localEntityId;
        } catch (IllegalAccessException exception) {
            return false;
        }
    }

    private static boolean isDamageVelocityPair(long first, long second) {
        return first != Long.MIN_VALUE && second != Long.MIN_VALUE
                && Math.abs(first - second) <= DAMAGE_FRAME_WINDOW_NANOS;
    }

    private void ensureAttached(Minecraft minecraft) {
        Channel channel = resolveChannel(minecraft);
        if (channel == null || channel == this.attachedChannel) return;
        detach();
        this.attachedChannel = channel;
        channel.eventLoop().execute(new Runnable() {
            @Override public void run() {
                Channel active = StateDrivenFriction.this.attachedChannel;
                if (active == null || !active.isOpen()
                        || active.pipeline().get(HANDLER_NAME) != null) return;
                ChannelPipeline pipeline = active.pipeline();
                if (pipeline.get(VANILLA_PACKET_HANDLER) != null) {
                    pipeline.addBefore(VANILLA_PACKET_HANDLER, HANDLER_NAME,
                            StateDrivenFriction.this.inboundObserver);
                } else {
                    pipeline.addLast(HANDLER_NAME, StateDrivenFriction.this.inboundObserver);
                }
            }
        });
    }

    private void detach() {
        final Channel channel = this.attachedChannel;
        this.attachedChannel = null;
        if (channel == null || !channel.isOpen()) return;
        channel.eventLoop().execute(new Runnable() {
            @Override public void run() {
                if (channel.pipeline().get(HANDLER_NAME) != null) {
                    channel.pipeline().remove(HANDLER_NAME);
                }
            }
        });
    }

    private static Channel resolveChannel(Minecraft minecraft) {
        if (!(minecraft.getNetHandler() instanceof NetHandlerPlayClient)) return null;
        try {
            NetworkManager manager = (NetworkManager) NET_MANAGER.get(minecraft.getNetHandler());
            return manager == null ? null : (Channel) CHANNEL.get(manager);
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static String text(int length, long... blocks) {
        char[] output = new char[length];
        for (int index = 0; index < length; index++) {
            long packed = blocks[index >> 2];
            output[index] = (char)(((packed >>> ((index & 3) << 4)) & 0xFFFFL) ^ 0x5A5AL);
        }
        return new String(output);
    }
}

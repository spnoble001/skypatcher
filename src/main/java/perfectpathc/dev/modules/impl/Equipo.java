package perfectpathc.dev.modules.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IChatComponent;
import org.lwjgl.opengl.GL11;

/** Stateless Forge adapter for Rust-owned, session-only team membership. */
@Mod(keybind = 0)
public final class Equipo extends Module {

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 4) return Boolean.valueOf(handleCommand((String) args[0]));
        if (commandId == 10) return Boolean.valueOf(contiene((EntityPlayer) args[0]));
        if (commandId == 16) { onRenderWorld(); return null; }
        return super.invokeCommand(commandId, args);
    }
    public Equipo() {
        this.sl.add(new Settings(text(19,
                0x5a3e5a345a3f5a08L, 0x5a205a335a285a3fL,
                0x5a3f5a7a5a285a3bL, 0x5a335a375a3f5a34L,
                0x00005a295a355a3dL, 0L, 0L, 0L, 0L).toCharArray(), this, false));
        registerSettings();
        this.name = text(6, 0x5a335a2f5a2b5a1fL, 0x000000005a355a2aL,
                0L, 0L, 0L, 0L, 0L, 0L, 0L);
        if (NativeBridge.isNativeAvailable()) {
            try { NativeBridge.initializeEquipo(); } catch (IllegalStateException ignored) { }
        }
    }

    @Override public char getSort() { return 'e'; }

    public boolean contiene(UUID uuid) {
        if (uuid == null || !NativeBridge.isNativeAvailable()) return false;
        try { return NativeBridge.equipoContains(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); }
        catch (IllegalStateException ignored) { return false; }
    }

    public boolean contiene(EntityPlayer player) { return player != null && contiene(player.getUniqueID()); }

    public boolean agregar(String ign) {
        if (ign == null) return false;
        int start = skipWhitespace(ign, 0);
        int end = tokenEnd(ign, start);
        if (start == end || skipWhitespace(ign, end) != ign.length()) return false;
        TeamPlayer player = findPlayer(ign, start, end);
        return player != null && addPlayer(player.uuid, player.name);
    }

    public boolean eliminar(UUID uuid) {
        if (uuid == null || !NativeBridge.isNativeAvailable()) return false;
        try { return NativeBridge.equipoRemove(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); }
        catch (IllegalStateException ignored) { return false; }
    }

    public boolean handleCommand(String command) {
        if (command == null) return false;
        int commandStart = skipWhitespace(command, 0);
        int commandEnd = tokenEnd(command, commandStart);
        int commandLength = commandEnd - commandStart;
        int commandHash = tokenHash(command, commandStart, commandEnd);
        boolean fullCommand = commandLength == 7 && commandHash == 874988525;
        boolean shortCommand = commandLength == 2 && commandHash == 1527;
        if (!fullCommand && !shortCommand) return false;
        int argumentStart = skipWhitespace(command, commandEnd);
        if (argumentStart == command.length()) {
            announceUsage();
            return true;
        }
        int argumentEnd = tokenEnd(command, argumentStart);
        if (skipWhitespace(command, argumentEnd) != command.length()) {
            announceUsage();
            return true;
        }
        TeamPlayer player = findPlayer(command, argumentStart, argumentEnd);
        if (player == null) {
            announce(text(26,
                    0x5a295a7a5a355a14L, 0x5a345a3f5a7a5a3fL,
                    0x5a2e5a345a355a39L, 0x5a3b5a7a5aa95a28L,
                    0x5a2f5a305a7a5a36L, 0x5a355a3e5a3b5a3dL,
                    0x000000005a745a28L, 0L, 0L));
            return true;
        }
        String playerName = player.name;
        int toggleResult = togglePlayer(player);
        if (toggleResult == 1) {
            announce(playerName + text(19,
                    0x5a3b5aab5a3b5a7aL, 0x5a355a3e5a335a3eL,
                    0x5a7a5a365a3b5a7aL, 0x5a335a2f5a2b5a3fL,
                    0x00005a745a355a2aL, 0L, 0L, 0L, 0L));
        } else if (toggleResult == 2) {
            announce(playerName + text(22,
                    0x5a335a365a3f5a7aL, 0x5a3b5a345a335a37L,
                    0x5a3e5a7a5a355a3eL, 0x5a3f5a7a5a365a3fL,
                    0x5a2a5a335a2f5a2bL, 0x000000005a745a35L,
                    0L, 0L, 0L));
        } else {
            announce(text(26,
                    0x5a295a7a5a355a14L, 0x5a345a3f5a7a5a3fL,
                    0x5a2e5a345a355a39L, 0x5a3b5a7a5aa95a28L,
                    0x5a2f5a305a7a5a36L, 0x5a355a3e5a3b5a3dL,
                    0x000000005a745a28L, 0L, 0L));
        }
        return true;
    }

    private int togglePlayer(TeamPlayer player) {
        if (player == null || player.uuid == null || !NativeBridge.isNativeAvailable()) return 0;
        long most = player.uuid.getMostSignificantBits();
        long least = player.uuid.getLeastSignificantBits();
        try {
            if (NativeBridge.equipoContains(most, least)) {
                return NativeBridge.equipoRemove(most, least) ? 2 : 0;
            }
            return NativeBridge.equipoAdd(most, least, player.name) ? 1 : 0;
        } catch (IllegalStateException ignored) {
            return 0;
        }
    }

    private boolean addPlayer(UUID uuid, String name) {
        if (uuid == null || !NativeBridge.isNativeAvailable()) return false;
        try { return NativeBridge.equipoAdd(
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), name); }
        catch (IllegalStateException ignored) { return false; }
    }

    /** Stable snapshot consumed by the ClickGUI; callers cannot mutate native state. */
    public List<MemberView> miembros() {
        if (!NativeBridge.isNativeAvailable()) return Collections.emptyList();
        try {
            int count = Math.max(0, Math.min(256, NativeBridge.equipoCount()));
            List<MemberView> members = new ArrayList<MemberView>(count);
            for (int index = 0; index < count; index++) {
                UUID uuid = new UUID(NativeBridge.equipoMemberMost(index),
                        NativeBridge.equipoMemberLeast(index));
                String name = NativeBridge.equipoMemberName(index);
                if (name.length() == 0) {
                    name = resolvePlayerName(uuid);
                    if (name != null) {
                        // Migrates UUID-only entries without changing membership.
                        NativeBridge.equipoAdd(uuid.getMostSignificantBits(),
                                uuid.getLeastSignificantBits(), name);
                    }
                }
                if (name == null || name.length() == 0) name = shortUuid(uuid);
                members.add(new MemberView(name, uuid));
            }
            return members;
        } catch (IllegalStateException ignored) {
            return Collections.emptyList();
        }
    }

    private String resolvePlayerName(UUID uuid) {
        if (uuid == null) return null;
        if (this.minecraft.theWorld != null) {
            for (int index = 0; index < this.minecraft.theWorld.playerEntities.size(); index++) {
                EntityPlayer player = this.minecraft.theWorld.playerEntities.get(index);
                if (player != null && uuid.equals(player.getUniqueID())) return player.getName();
            }
        }
        if (this.minecraft.getNetHandler() != null) {
            for (NetworkPlayerInfo info : this.minecraft.getNetHandler().getPlayerInfoMap()) {
                GameProfile profile = info == null ? null : info.getGameProfile();
                if (profile != null && uuid.equals(profile.getId())) return profile.getName();
            }
        }
        return null;
    }

    private static String shortUuid(UUID uuid) {
        String value = uuid.toString();
        return value.substring(0, 8);
    }

    public static final class MemberView {
        private final String name;
        private final UUID uuid;

        private MemberView(String name, UUID uuid) {
            this.name = name;
            this.uuid = uuid;
        }

        public String getName() { return this.name; }
        public UUID getUuid() { return this.uuid; }
    }

    /** Resolve from loaded entities first, then from the tab list for remote players. */
    private TeamPlayer findPlayer(String value, int start, int end) {
        int length = end - start;
        if (this.minecraft.theWorld != null) {
            for (int index = 0; index < this.minecraft.theWorld.playerEntities.size(); index++) {
                EntityPlayer player = this.minecraft.theWorld.playerEntities.get(index);
                if (player == null) continue;
                String name = player.getName();
                if (matchesName(name, value, start, length)) {
                    return new TeamPlayer(name, usableUuid(player.getUniqueID(), name));
                }
            }
        }
        if (this.minecraft.getNetHandler() != null) {
            for (NetworkPlayerInfo info : this.minecraft.getNetHandler().getPlayerInfoMap()) {
                GameProfile profile = info == null ? null : info.getGameProfile();
                String profileName = profile == null ? null : profile.getName();
                UUID uuid = profile == null ? null : profile.getId();
                IChatComponent display = info == null ? null : info.getDisplayName();
                String displayName = display == null ? null : display.getUnformattedText();
                if (matchesName(profileName, value, start, length)
                        || matchesTabDisplayName(displayName, value, start, length)) {
                    String canonicalName = profileName != null ? profileName : displayName;
                    return new TeamPlayer(canonicalName, usableUuid(uuid, canonicalName));
                }
            }
        }
        return null;
    }

    private static boolean matchesName(String name, String value, int start, int length) {
        if (name == null || length <= 0) return false;
        String plainName = stripFormatting(name);
        return plainName.length() == length
                && plainName.regionMatches(true, 0, value, start, length);
    }

    /** Tab-list entries often include rank text or a server nickname. Match the
     * requested IGN as a complete Minecraft-name token, never as a substring. */
    private static boolean matchesTabDisplayName(String name, String value, int start, int length) {
        if (name == null || length <= 0) return false;
        String plainName = stripFormatting(name);
        for (int index = 0; index + length <= plainName.length(); index++) {
            if (!plainName.regionMatches(true, index, value, start, length)) continue;
            boolean leftBoundary = index == 0 || !isNameCharacter(plainName.charAt(index - 1));
            int end = index + length;
            boolean rightBoundary = end == plainName.length()
                    || !isNameCharacter(plainName.charAt(end));
            if (leftBoundary && rightBoundary) return true;
        }
        return false;
    }

    private static boolean isNameCharacter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9' || value == '_';
    }

    /** Some offline/proxy tab entries omit the profile UUID. Use Minecraft's
     * deterministic offline UUID formula so lookup still works and later
     * EntityPlayer membership comparisons use the same identity. */
    private static UUID usableUuid(UUID uuid, String name) {
        if (uuid != null) return uuid;
        return name == null ? null : UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String stripFormatting(String value) {
        StringBuilder plain = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\u00A7' && index + 1 < value.length()) {
                index++;
            } else {
                plain.append(character);
            }
        }
        return plain.toString();
    }

    private static final class TeamPlayer {
        private final String name;
        private final UUID uuid;

        private TeamPlayer(String name, UUID uuid) {
            this.name = name;
            this.uuid = uuid;
        }
    }

    private void announceUsage() {
        announce(text(18,
                0x5a605a355a295a0fL, 0x5a2b5a3f5a745a7aL,
                0x5a355a2a5a335a2fL, 0x5a1d5a135a665a7aL,
                0x000000005a645a14L, 0L, 0L, 0L, 0L));
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private static int tokenEnd(String value, int index) {
        while (index < value.length() && !Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private static int tokenHash(String value, int start, int end) {
        int hash = 0;
        for (int index = start; index < end; index++) {
            hash = 31 * hash + Character.toLowerCase(value.charAt(index));
        }
        return hash;
    }

    public void onRenderWorld() {
        if (!this.enabled || this.minecraft.theWorld == null || this.minecraft.thePlayer == null) return;
        boolean enemies = !this.sl.isEmpty() && this.sl.get(0).getValBoolean();
        double x = this.minecraft.getRenderManager().viewerPosX, y = this.minecraft.getRenderManager().viewerPosY, z = this.minecraft.getRenderManager().viewerPosZ;
        for (int index = 0; index < this.minecraft.theWorld.playerEntities.size(); index++) {
            EntityPlayer player = this.minecraft.theWorld.playerEntities.get(index);
            if (player == null || player == this.minecraft.thePlayer || player.isDead) continue;
            boolean teammate = contiene(player);
            if (!teammate && !enemies) continue;
            AxisAlignedBB box = player.getEntityBoundingBox();
            renderBox(box.minX - x, box.minY - y, box.minZ - z,
                    box.maxX - x, box.maxY - y, box.maxZ - z,
                    teammate ? 0xFF1B1B80 : 0xFFFF2F30);
        }
    }

    private void renderBox(double x1, double y1, double z1,
                           double x2, double y2, double z2, int color) {
        GlStateManager.pushMatrix(); GlStateManager.disableTexture2D(); GlStateManager.enableBlend(); GlStateManager.disableDepth();
        GlStateManager.color(((color >>> 16) & 255) / 255.0F, ((color >>> 8) & 255) / 255.0F, (color & 255) / 255.0F, ((color >>> 24) & 255) / 255.0F);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x2, y1, z1);
        GL11.glVertex3d(x2, y1, z1); GL11.glVertex3d(x2, y1, z2);
        GL11.glVertex3d(x2, y1, z2); GL11.glVertex3d(x1, y1, z2);
        GL11.glVertex3d(x1, y1, z2); GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x1, y2, z1); GL11.glVertex3d(x2, y2, z1);
        GL11.glVertex3d(x2, y2, z1); GL11.glVertex3d(x2, y2, z2);
        GL11.glVertex3d(x2, y2, z2); GL11.glVertex3d(x1, y2, z2);
        GL11.glVertex3d(x1, y2, z2); GL11.glVertex3d(x1, y2, z1);
        GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x1, y2, z1);
        GL11.glVertex3d(x2, y1, z1); GL11.glVertex3d(x2, y2, z1);
        GL11.glVertex3d(x2, y1, z2); GL11.glVertex3d(x2, y2, z2);
        GL11.glVertex3d(x1, y1, z2); GL11.glVertex3d(x1, y2, z2);
        GL11.glEnd();
        GlStateManager.enableDepth(); GlStateManager.disableBlend(); GlStateManager.enableTexture2D(); GlStateManager.popMatrix();
    }

    private void announce(String message) {
        if (this.minecraft.thePlayer != null) {
            this.minecraft.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    text(15, 0x5a285a3f5a0a5a01L, 0x5a2e5a395a3f5a3cL,
                            0x5a395a2e5a3b5a0aL, 0x00005a7a5a075a32L,
                            0L, 0L, 0L, 0L, 0L) + message));
        }
    }

    private static String text(int length, long a, long b, long c, long d,
                               long e, long f, long g, long h, long i) {
        char[] output = new char[length];
        for (int index = 0; index < length; index++) {
            long packed;
            switch (index >> 2) {
                case 0: packed = a; break;
                case 1: packed = b; break;
                case 2: packed = c; break;
                case 3: packed = d; break;
                case 4: packed = e; break;
                case 5: packed = f; break;
                case 6: packed = g; break;
                case 7: packed = h; break;
                default: packed = i; break;
            }
            output[index] = (char)(((packed >>> ((index & 3) << 4)) & 0xFFFFL) ^ 0x5A5AL);
        }
        return new String(output);
    }
}

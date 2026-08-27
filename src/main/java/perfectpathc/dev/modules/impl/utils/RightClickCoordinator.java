package perfectpathc.dev.modules.impl.utils;

import perfectpathc.dev.modules.impl.NativeBridge;

public final class RightClickCoordinator {
    private static final int OWNER_NONE = 0;
    private static final int OWNER_SIMPLE_ASSIST = 1;
    private static final int OWNER_PRE_PATCH = 2;
    private static final int OWNER_THROW_POT = 3;
    private static final int OWNER_SIMPLE_ASSIST_3D = 4;
    private static Owner simpleAssistOwner;
    private static Owner prePatchOwner;
    private static Owner throwPotOwner;
    private static Owner simpleAssist3DOwner;

    public interface Owner {
        void onRightClickControlLost();
    }

    private RightClickCoordinator() {
    }

    public static boolean acquire(Owner requester, int requestedPriority) {
        int displaced = NativeBridge.acquireRightClick(ownerId(requester), requestedPriority);
        if (displaced < OWNER_NONE) {
            return false;
        }
        notifyControlLost(displaced);
        return true;
    }

    public static void release(Owner requester) {
        NativeBridge.releaseRightClick(ownerId(requester));
    }

    public static boolean isOwner(Owner requester) {
        return NativeBridge.isRightClickOwner(ownerId(requester));
    }

    public static String getOwnerName() {
        switch (NativeBridge.rightClickOwner()) {
            case OWNER_SIMPLE_ASSIST: return "SimpleAssist";
            case OWNER_PRE_PATCH: return "PrePatch";
            case OWNER_THROW_POT: return "ThrowPot";
            case OWNER_SIMPLE_ASSIST_3D: return "SimpleAssist3D";
            default: return "none";
        }
    }

    public static int getPriority() {
        return NativeBridge.rightClickPriority();
    }

    private static int ownerId(Owner owner) {
        String className = owner == null ? "" : owner.getClass().getName();
        if ("perfectpathc.dev.modules.impl.SimpleAssist".equals(className)) {
            simpleAssistOwner = owner;
            return OWNER_SIMPLE_ASSIST;
        }
        if ("perfectpathc.dev.modules.impl.PrePatch".equals(className)) {
            prePatchOwner = owner;
            return OWNER_PRE_PATCH;
        }
        if ("perfectpathc.dev.modules.impl.ThrowPot".equals(className)) {
            throwPotOwner = owner;
            return OWNER_THROW_POT;
        }
        if ("perfectpathc.dev.modules.impl.SimpleAssist3D".equals(className)) {
            simpleAssist3DOwner = owner;
            return OWNER_SIMPLE_ASSIST_3D;
        }
        throw new IllegalArgumentException("RightClick owner no registrado");
    }

    private static void notifyControlLost(int ownerId) {
        if (ownerId == OWNER_SIMPLE_ASSIST) {
            if (simpleAssistOwner != null) simpleAssistOwner.onRightClickControlLost();
        } else if (ownerId == OWNER_PRE_PATCH) {
            if (prePatchOwner != null) prePatchOwner.onRightClickControlLost();
        } else if (ownerId == OWNER_THROW_POT) {
            if (throwPotOwner != null) throwPotOwner.onRightClickControlLost();
        } else if (ownerId == OWNER_SIMPLE_ASSIST_3D) {
            if (simpleAssist3DOwner != null) simpleAssist3DOwner.onRightClickControlLost();
        }
    }
}

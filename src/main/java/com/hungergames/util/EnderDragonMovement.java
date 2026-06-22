package com.hungergames.util;

import org.bukkit.Location;
import org.bukkit.entity.EnderDragon;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Steers an ender dragon through its native phase controller so body parts animate correctly.
 * Forced teleports / {@link EnderDragon.Phase#HOVER} desync the multi-part dragon model.
 */
public final class EnderDragonMovement {

    public enum Style {
        /** Smooth orbit-style flight via the holding pattern phase. */
        PATROL,
        /** Aggressive dive toward a point via the charge phase. */
        CHARGE
    }

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized;
    private static volatile boolean nmsAvailable;

    private static Method getHandleMethod;
    private static Method getPhaseManagerMethod;
    private static Field phaseManagerField;
    private static Method chargeSetTargetMethod;
    private static Constructor<?> vec3Constructor;
    private static Object chargePhaseInstance;
    private static Object holdingPhaseInstance;
    private static Field holdingTargetField;

    private EnderDragonMovement() {
    }

    /**
     * Flies the dragon toward {@code target} using the native dragon phase system.
     *
     * @return true when NMS steering succeeded
     */
    public static boolean steer(EnderDragon dragon, Location target, Style style) {
        if (dragon == null || target == null || target.getWorld() == null) {
            return false;
        }
        if (!initNms(dragon)) {
            applyCirclingFallback(dragon);
            return false;
        }

        try {
            Object vec3 = vec3Constructor.newInstance(target.getX(), target.getY(), target.getZ());
            EnderDragon.Phase desiredPhase;
            if (style == Style.CHARGE) {
                chargeSetTargetMethod.invoke(chargePhaseInstance, vec3);
                desiredPhase = EnderDragon.Phase.CHARGE_PLAYER;
            } else {
                holdingTargetField.set(holdingPhaseInstance, vec3);
                desiredPhase = EnderDragon.Phase.CIRCLING;
            }

            boolean switchingPhase = dragon.getPhase() != desiredPhase;
            if (switchingPhase) {
                dragon.setPhase(desiredPhase);
            }
            // begin() can clear path targets when a phase starts — set again after switching.
            if (style == Style.CHARGE) {
                chargeSetTargetMethod.invoke(chargePhaseInstance, vec3);
            } else {
                holdingTargetField.set(holdingPhaseInstance, vec3);
            }
            return true;
        } catch (ReflectiveOperationException ex) {
            nmsAvailable = false;
            applyCirclingFallback(dragon);
            return false;
        }
    }

    private static void applyCirclingFallback(EnderDragon dragon) {
        if (dragon.getPhase() == EnderDragon.Phase.HOVER
                || dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER) {
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
        }
    }

    private static boolean initNms(EnderDragon dragon) {
        if (initialized) {
            return nmsAvailable;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return nmsAvailable;
            }
            initialized = true;
            try {
                getHandleMethod = dragon.getClass().getMethod("getHandle");
                Class<?> nmsDragonClass = getHandleMethod.getReturnType();
                getPhaseManagerMethod = findMethod(nmsDragonClass, "getPhaseManager");
                if (getPhaseManagerMethod == null) {
                    phaseManagerField = nmsDragonClass.getDeclaredField("phaseManager");
                    phaseManagerField.setAccessible(true);
                }

                Class<?> vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
                vec3Constructor = vec3Class.getConstructor(double.class, double.class, double.class);

                Class<?> chargePhaseClass = Class.forName(
                        "net.minecraft.world.entity.boss.enderdragon.phases.DragonChargePlayerPhase");
                chargeSetTargetMethod = findMethod(chargePhaseClass, "setTarget", vec3Class);

                Class<?> holdingPhaseClass = Class.forName(
                        "net.minecraft.world.entity.boss.enderdragon.phases.DragonHoldingPatternPhase");
                holdingTargetField = holdingPhaseClass.getDeclaredField("targetLocation");
                holdingTargetField.setAccessible(true);

                Object nmsDragon = getHandleMethod.invoke(dragon);
                Object phaseManager = getPhaseManagerMethod != null
                        ? getPhaseManagerMethod.invoke(nmsDragon)
                        : phaseManagerField.get(nmsDragon);
                chargePhaseInstance = resolvePhase(phaseManager, chargePhaseClass);
                holdingPhaseInstance = resolvePhase(phaseManager, holdingPhaseClass);

                nmsAvailable = chargePhaseInstance != null
                        && holdingPhaseInstance != null
                        && chargeSetTargetMethod != null
                        && holdingTargetField != null
                        && vec3Constructor != null;
            } catch (ReflectiveOperationException ex) {
                nmsAvailable = false;
            }
        }
        return nmsAvailable;
    }

    private static Object resolvePhase(Object phaseManager, Class<?> phaseClass)
            throws ReflectiveOperationException {
        Field phasesField = phaseManager.getClass().getDeclaredField("phases");
        phasesField.setAccessible(true);
        Object[] phases = (Object[]) phasesField.get(phaseManager);
        for (Object phase : phases) {
            if (phase != null && phaseClass.isInstance(phase)) {
                return phase;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }
}

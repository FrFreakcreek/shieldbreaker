package com.example.shieldbreaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/**
 * Auto Shield Breaker for Minecraft 1.21.11 (Fabric, Yarn mappings).
 *
 * Differences from the 1.20.4 version:
 *  - PlayerInventory.selectedSlot is private -> getSelectedSlot()/setSelectedSlot()
 *  - Shields are data-driven (blocks_attacks component), ShieldItem no longer exists
 *    -> LivingEntity.isBlocking() is used instead
 *  - AxeItem has no getAttackDamage() -> axes are found via ItemTags.AXES and ranked by material
 *  - KeyBinding categories are now KeyBinding.Category objects
 */
public class ShieldBreakerClient implements ClientModInitializer {

    private enum State { IDLE, WAIT_ATTACK, WAIT_RESTORE }

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("shieldbreaker", "main"));
    private static KeyBinding toggleKey;

    private static State state = State.IDLE;
    private static int originalSlot = -1;
    private static int axeSlot = -1;
    private static int ticks = 0;
    private static Entity pendingTarget = null;
    private static boolean internalAttack = false; // re-entrancy guard for our delayed attack
    private static final Random RNG = new Random();

    @Override
    public void onInitializeClient() {
        ShieldBreakerConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shieldbreaker.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(ShieldBreakerClient::onTick);
        AttackEntityCallback.EVENT.register(ShieldBreakerClient::onAttack);
    }

    // ------------------------------------------------------------------
    // Attack hook: runs BEFORE the attack packet is created
    // ------------------------------------------------------------------
    private static ActionResult onAttack(PlayerEntity player, World world, Hand hand,
                                         Entity target, @Nullable EntityHitResult hit) {
        ShieldBreakerConfig cfg = ShieldBreakerConfig.get();

        // The callback also fires for the integrated server's player in singleplayer;
        // only react to the real client-side player.
        if (!(player instanceof ClientPlayerEntity) || internalAttack || hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (!cfg.enabled || player.isSpectator()) return ActionResult.PASS;

        // A delayed attack is already queued: swallow extra clicks.
        if (state == State.WAIT_ATTACK) return ActionResult.FAIL;

        if (!(target instanceof LivingEntity living) || !isBlockingWithShield(living)) {
            return ActionResult.PASS;
        }
        if (target instanceof PlayerEntity ? !cfg.targetPlayers : !cfg.targetMobs) {
            return ActionResult.PASS;
        }

        PlayerInventory inv = player.getInventory();

        // Already holding an axe -> nothing to switch, just extend the restore timer.
        if (player.getMainHandStack().isIn(ItemTags.AXES)) {
            if (state == State.WAIT_RESTORE) ticks = restoreDelay();
            return ActionResult.PASS;
        }

        // Player manually changed slots during a restore window: drop the old state.
        if (state == State.WAIT_RESTORE) state = State.IDLE;

        int best = findBestAxeSlot(inv, cfg);
        if (best < 0) return ActionResult.PASS; // no axe in hotbar

        originalSlot = inv.getSelectedSlot();
        axeSlot = best;
        inv.setSelectedSlot(best); // vanilla's syncSelectedSlot() sends the packet

        int delay = computeDelay(cfg);
        if (delay <= 0) {
            // Immediate: attackEntity() calls syncSelectedSlot() right after this
            // callback returns, so the server gets "select slot" then "attack".
            beginRestore(cfg);
            return ActionResult.PASS;
        }

        // Delayed: cancel this click, wait N ticks, then attack with the axe.
        pendingTarget = target;
        ticks = delay;
        state = State.WAIT_ATTACK;
        return ActionResult.FAIL;
    }

    // ------------------------------------------------------------------
    // Tick handler: keybind, delayed attack, switch-back
    // ------------------------------------------------------------------
    private static void onTick(MinecraftClient mc) {
        ShieldBreakerConfig cfg = ShieldBreakerConfig.get();

        while (toggleKey.wasPressed()) {
            cfg.enabled = !cfg.enabled;
            ShieldBreakerConfig.save();
            if (!cfg.enabled) abortAndRestore(mc);
            if (mc.player != null) {
                mc.player.sendMessage(Text.translatable(
                        cfg.enabled ? "shieldbreaker.enabled" : "shieldbreaker.disabled"), true);
            }
        }

        if (mc.player == null || mc.interactionManager == null) {
            resetState();
            return;
        }

        switch (state) {
            case WAIT_ATTACK -> {
                if (--ticks > 0) return;

                Entity target = pendingTarget;
                boolean stillValid = target != null
                        && target.isAlive()
                        && mc.player.distanceTo(target) <= 4.5f
                        && mc.crosshairTarget instanceof EntityHitResult ehr
                        && ehr.getEntity() == target
                        && mc.player.getInventory().getSelectedSlot() == axeSlot;

                if (stillValid) {
                    internalAttack = true;
                    try {
                        // attackEntity() syncs the slot and sends the attack packet.
                        // The swing animation already happened on the original click.
                        mc.interactionManager.attackEntity(mc.player, target);
                    } finally {
                        internalAttack = false;
                    }
                }
                pendingTarget = null;
                beginRestore(cfg);
            }
            case WAIT_RESTORE -> {
                if (--ticks > 0) return;
                restoreOriginalSlot(mc);
                state = State.IDLE;
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private static boolean isBlockingWithShield(LivingEntity e) {
        // isBlocking() covers any item with the blocks_attacks component (vanilla shields)
        // and includes vanilla's warm-up delay, matching what the server considers blocking.
        return e.isUsingItem() && e.isBlocking();
    }

    /** Base score by axe material, derived from the item id so modded "xxx_axe" items still work. */
    private static double materialScore(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.startsWith("netherite")) return 10.0;
        if (path.startsWith("diamond"))   return 9.2; // same damage as iron, more durable
        if (path.startsWith("iron"))      return 9.0;
        if (path.startsWith("copper"))    return 7.1;
        if (path.startsWith("stone"))     return 7.0;
        if (path.startsWith("golden"))    return 6.0;
        if (path.startsWith("wooden"))    return 6.0;
        return 8.0; // unknown/modded axe: assume decent
    }

    private static int enchantLevel(ItemStack stack, net.minecraft.registry.RegistryKey<Enchantment> key) {
        var enchants = stack.getEnchantments();
        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
            if (entry.matchesKey(key)) return enchants.getLevel(entry);
        }
        return 0;
    }

    private static int findBestAxeSlot(PlayerInventory inv, ShieldBreakerConfig cfg) {
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isIn(ItemTags.AXES)) continue;
            if (stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= cfg.minAxeDurability) continue;

            double score = materialScore(stack);
            score += enchantLevel(stack, Enchantments.SHARPNESS) * 0.5;
            score += enchantLevel(stack, Enchantments.UNBREAKING) * 0.05;
            score += stack.getMaxDamage() * 0.0001; // tie-breaker: more durable wins

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static int computeDelay(ShieldBreakerConfig cfg) {
        int d = Math.max(0, Math.min(3, cfg.switchDelayTicks));
        if (d == 0) return 0;
        return cfg.randomizeDelay ? 1 + RNG.nextInt(d) : d;
    }

    private static int restoreDelay() {
        return Math.max(1, ShieldBreakerConfig.get().switchBackDelayTicks);
    }

    private static void beginRestore(ShieldBreakerConfig cfg) {
        if (cfg.switchBack) {
            ticks = restoreDelay();
            state = State.WAIT_RESTORE;
        } else {
            state = State.IDLE;
        }
    }

    private static void restoreOriginalSlot(MinecraftClient mc) {
        if (mc.player == null || originalSlot < 0) return;
        PlayerInventory inv = mc.player.getInventory();
        // Only restore if the player hasn't scrolled elsewhere themselves.
        if (inv.getSelectedSlot() == axeSlot) inv.setSelectedSlot(originalSlot);
    }

    private static void abortAndRestore(MinecraftClient mc) {
        if (state != State.IDLE) restoreOriginalSlot(mc);
        resetState();
    }

    private static void resetState() {
        state = State.IDLE;
        pendingTarget = null;
        originalSlot = axeSlot = -1;
        ticks = 0;
    }
}

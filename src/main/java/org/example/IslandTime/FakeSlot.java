package org.example.islandtime;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;

/**
 * A minimal Slot that always returns a fixed ItemStack.
 *
 * Used by AbstractContainerScreenMixin to temporarily remap focusedSlot so that
 * mods reading focusedSlot during keyPressed (e.g. Firmament's copy
 * bindings) see the overlay-hovered item rather than the backpack shell.
 *
 * The null inventory is intentional — nothing should ever mutate this
 * slot, and all mutating/query methods are overridden to be no-ops.
 */
@SuppressWarnings("DataFlowIssue")
public class FakeSlot extends Slot {

    private final ItemStack fixedStack;

    public FakeSlot(ItemStack stack) {
        super(null, 0, 0, 0);
        this.fixedStack = stack;
    }

    @Override public ItemStack getItem()                               { return fixedStack; }
    @Override public boolean  hasItem()                               { return !fixedStack.isEmpty(); }
    @Override public boolean  mayPlace(ItemStack stack)               { return false; }
    @Override public boolean  mayPickup(Player player)                { return false; }
    @Override public void     set(ItemStack stack)                    { /* no-op */ }
    @Override public void     setByPlayer(ItemStack stack)            { /* no-op */ }
    @Override public ItemStack remove(int amount)                     { return ItemStack.EMPTY; }
}

package org.example.islandtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Rotations;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Everything the greenhouse blueprint needs to know about real armor stands: reading one in the world
 * into a {@link GreenhouseBlueprint.Stand} (what it wears and holds, which way it faces, how it is
 * posed), and building a stand that exists only on the client from one, to draw a ghost of it.
 *
 * <p>Items are stored the way the favourites feature stores them: the item's own codec to NBT, as
 * compressed Base64. That keeps everything about an item, a skull's texture included.
 */
final class GreenhouseStandItems {

    private static final int DECODE_CACHE_SIZE = 256;

    /** Items already turned back from text, since the same few heads are on most of a greenhouse's stands. */
    private static final Map<String, ItemStack> DECODED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
            return size() > DECODE_CACHE_SIZE;
        }
    };

    private GreenhouseStandItems() {}

    // -------------------------------------------------------------------------
    // Reading a stand
    // -------------------------------------------------------------------------

    /**
     * Reads an armor stand that is somewhere in the greenhouse area.
     *
     * @param areaX     the world x of the area's north west corner
     * @param areaZ     the world z of the area's north west corner
     * @param keepItems whether to keep the items themselves (for copying), or only what is needed to
     *                  compare them (for the checks that run every half second)
     * @return the stand, or null if it wears and holds nothing, which makes it a hologram
     */
    static GreenhouseBlueprint.Stand read(ArmorStand stand, int areaX, int areaZ, RegistryAccess registries,
                                          boolean keepItems) {
        List<GreenhouseBlueprint.Gear> gear = new ArrayList<>();
        String head = "";
        for (EquipmentSlot slot : slotsInOrder()) {
            ItemStack item = stand.getItemBySlot(slot);
            if (item.isEmpty()) continue;
            String name = item.getHoverName().getString();
            if (slot == EquipmentSlot.HEAD) head = name;
            Identifier id = BuiltInRegistries.ITEM.getKey(item.getItem());
            gear.add(new GreenhouseBlueprint.Gear(slot.getName(), id + "|" + name, name,
                    keepItems ? encode(item, registries) : ""));
        }
        if (gear.isEmpty()) return null;

        Vec3 pos = stand.position();
        GreenhouseBlueprint.Stand read = new GreenhouseBlueprint.Stand(head, pos.x - areaX,
                pos.y - GreenhouseGeometry.CROP_Y, pos.z - areaZ, stand.getBbWidth(), stand.getBbHeight());
        read.gear = gear;
        read.yaw = GreenhouseBlueprint.normalizeYaw(stand.getYRot());
        read.flags = (stand.isSmall() ? GreenhouseBlueprint.FLAG_SMALL : 0)
                | (stand.showArms() ? GreenhouseBlueprint.FLAG_ARMS : 0)
                | (stand.showBasePlate() ? 0 : GreenhouseBlueprint.FLAG_NO_BASE_PLATE)
                | (stand.isMarker() ? GreenhouseBlueprint.FLAG_MARKER : 0);
        read.invisible = stand.isInvisible();
        read.pose = poseOf(stand);
        return read;
    }

    /** The slots a stand can wear or hold something in, in the order {@link GreenhouseBlueprint#SLOTS} lists them. */
    private static List<EquipmentSlot> slotsInOrder() {
        List<EquipmentSlot> slots = new ArrayList<>();
        for (String name : GreenhouseBlueprint.SLOTS) {
            EquipmentSlot slot = slotNamed(name);
            if (slot != null) slots.add(slot);
        }
        return slots;
    }

    private static EquipmentSlot slotNamed(String name) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getName().equals(name)) return slot;
        }
        return null;
    }

    private static float[] poseOf(ArmorStand stand) {
        Rotations[] parts = {stand.getHeadPose(), stand.getBodyPose(), stand.getLeftArmPose(),
                stand.getRightArmPose(), stand.getLeftLegPose(), stand.getRightLegPose()};
        float[] pose = new float[GreenhouseBlueprint.POSE_VALUES];
        for (int i = 0; i < parts.length; i++) {
            pose[i * 3] = parts[i].x();
            pose[i * 3 + 1] = parts[i].y();
            pose[i * 3 + 2] = parts[i].z();
        }
        return pose;
    }

    // -------------------------------------------------------------------------
    // Items as text
    // -------------------------------------------------------------------------

    /** An item as compressed NBT in Base64, or "" if it can't be stored. */
    static String encode(ItemStack item, RegistryAccess registries) {
        try {
            CompoundTag tag = ItemStack.CODEC
                    .encode(item, RegistryOps.create(NbtOps.INSTANCE, registries), new CompoundTag())
                    .result()
                    .filter(e -> e instanceof CompoundTag)
                    .map(e -> (CompoundTag) e)
                    .orElse(null);
            if (tag == null) return "";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** An item stored by {@link #encode}, or {@link ItemStack#EMPTY} if it can't be read back. */
    static ItemStack decode(String data, RegistryAccess registries) {
        if (data == null || data.isEmpty()) return ItemStack.EMPTY;
        ItemStack cached = DECODED.get(data);
        if (cached != null) return cached;

        ItemStack item = ItemStack.EMPTY;
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(data)),
                    NbtAccounter.defaultQuota());
            item = ItemStack.CODEC
                    .parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag)
                    .result()
                    .orElse(ItemStack.EMPTY);
        } catch (IOException | RuntimeException e) {
            // Unreadable: stays empty
        }
        DECODED.put(data, item);
        return item;
    }

    // -------------------------------------------------------------------------
    // Building a ghost
    // -------------------------------------------------------------------------

    /**
     * Builds an armor stand that is never added to the world, wearing and posed like a wanted stand.
     *
     * @return the stand, or null if nothing it wears could be read back
     */
    static ArmorStand createGhost(ClientLevel level, GreenhouseBlueprint.Stand wanted, double x, double y, double z) {
        ArmorStand ghost = new ArmorStand(EntityType.ARMOR_STAND, level);
        ghost.setSilent(true);
        ghost.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) wanted.flags);
        ghost.setInvisible(wanted.invisible);

        if (wanted.pose != null && wanted.pose.length == GreenhouseBlueprint.POSE_VALUES) {
            float[] p = wanted.pose;
            ghost.setHeadPose(new Rotations(p[0], p[1], p[2]));
            ghost.setBodyPose(new Rotations(p[3], p[4], p[5]));
            ghost.setLeftArmPose(new Rotations(p[6], p[7], p[8]));
            ghost.setRightArmPose(new Rotations(p[9], p[10], p[11]));
            ghost.setLeftLegPose(new Rotations(p[12], p[13], p[14]));
            ghost.setRightLegPose(new Rotations(p[15], p[16], p[17]));
        }

        boolean any = false;
        for (GreenhouseBlueprint.Gear gear : wanted.gear) {
            EquipmentSlot slot = slotNamed(gear.slot);
            if (slot == null) continue;
            ItemStack item = decode(gear.data, level.registryAccess());
            if (item.isEmpty()) continue;
            ghost.setItemSlot(slot, item.copy());
            any = true;
        }
        if (!any) return null;

        float yaw = wanted.yaw;
        ghost.snapTo(x, y, z, yaw, 0.0f);
        ghost.yBodyRot = yaw;
        ghost.yBodyRotO = yaw;
        ghost.yHeadRot = yaw;
        ghost.yHeadRotO = yaw;
        return ghost;
    }
}

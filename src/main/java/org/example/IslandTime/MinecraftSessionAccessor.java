package org.example.islandtime.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the mod swap in a freshly renewed login while the game is running (see AccountManager). Minecraft only
 * reads these once, at startup, and otherwise keeps using the token it launched with until that expires.
 */
@Mixin(Minecraft.class)
public interface MinecraftSessionAccessor {

    @Mutable
    @Accessor("user")
    void islandtime$setUser(User user);

    @Mutable
    @Accessor("userApiService")
    void islandtime$setUserApiService(UserApiService service);

    @Mutable
    @Accessor("profileKeyPairManager")
    void islandtime$setProfileKeyPairManager(ProfileKeyPairManager manager);
}

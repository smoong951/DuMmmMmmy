
package net.mehvahdjukaar.dummmmmmy.common;

import dev.architectury.injectables.annotations.PlatformOnly;
import net.mehvahdjukaar.dummmmmmy.Dummmmmmy;
import net.mehvahdjukaar.dummmmmmy.configs.ClientConfigs;
import net.mehvahdjukaar.dummmmmmy.configs.CommonConfigs;
import net.mehvahdjukaar.dummmmmmy.network.ClientBoundDamageNumberMessage;
import net.mehvahdjukaar.dummmmmmy.network.ClientBoundSyncEquipMessage;
import net.mehvahdjukaar.dummmmmmy.network.ClientBoundUpdateAnimationMessage;
import net.mehvahdjukaar.dummmmmmy.network.NetworkHandler;
import net.mehvahdjukaar.moonlight.api.platform.ForgeHelper;
import net.mehvahdjukaar.moonlight.api.platform.PlatformHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.util.*;

public class TargetDummyEntity extends Mob {

    private static final EntityDataAccessor<Boolean> SHEARED = SynchedEntityData.defineId(TargetDummyEntity.class, EntityDataSerializers.BOOLEAN);

    //client values
    private float prevAnimationPosition = 0;
    // used to have an independent start for the animation, otherwise the phase of the animation depends ont he damage dealt
    private float shakeAmount = 0;
    private float prevShakeAmount = 0;

    // used to calculate the whole damage in one tick, in case there are multiple sources
    private int lastTickActuallyDamaged;
    // currently, recording damage taken
    private float totalDamageTakenInCombat;
    //has just been hit by critical?
    private boolean critical = false;
    private DummyMobType mobType = DummyMobType.UNDEFINED;
    //position of damage number in the semicircle
    private int damageNumberPos = 0;
    //needed because it's private, and we aren't calling le tick
    private final NonNullList<ItemStack> lastArmorItems = NonNullList.withSize(4, ItemStack.EMPTY);

    private final Map<ServerPlayer, Integer> currentlyAttacking = new HashMap<>();
    private DamageSource currentDamageSource = null;
    private boolean unbreakable = false;


    public TargetDummyEntity(EntityType<TargetDummyEntity> type, Level world) {
        super(type, world);
    }

    public TargetDummyEntity(Level world) {
        this(Dummmmmmy.TARGET_DUMMY.get(), world);
        this.xpReward = 0;
        Arrays.fill(this.armorDropChances, 1.1f);
    }

    public float getShake(float partialTicks) {
        return Mth.lerp(partialTicks, prevShakeAmount, shakeAmount);
    }

    public float getAnimationPosition(float partialTicks) {
        return Mth.lerp(partialTicks, prevAnimationPosition, animationPosition);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(SHEARED, false);
    }

    public boolean isSheared() {
        return this.entityData.get(SHEARED);
    }

    public void setSheared(boolean sheared) {
        this.entityData.set(SHEARED, sheared);
    }

    @Override
    public Packet<?> getAddEntityPacket() {
        return PlatformHelper.getEntitySpawnPacket(this);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Type", this.mobType.ordinal());
        tag.putInt("NumberPos", this.damageNumberPos);
        tag.putBoolean("Sheared", this.isSheared());
        if (this.unbreakable) tag.putBoolean("unbreakable", true);

        this.applyEquipmentModifiers();
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.mobType = DummyMobType.values()[tag.getInt("Type")];
        this.damageNumberPos = tag.getInt("NumberPos");
        this.setSheared(tag.getBoolean("Sheared"));
        if (tag.contains("unbreakable")) {
            this.unbreakable = tag.getBoolean("unbreakable");
        }
    }

    @Override
    public void setYBodyRot(float pOffset) {
        float r = this.getYRot();
        this.yRotO = r;
        this.yBodyRotO = this.yBodyRot = r;
    }

    @Override
    public void setYHeadRot(float pRotation) {
        float r = this.getYRot();
        this.yRotO = r;
        this.yHeadRotO = this.yHeadRot = r;
    }

    // dress it up! :D
    @Override
    public InteractionResult interactAt(Player player, Vec3 vec, InteractionHand hand) {
        boolean inventoryChanged = false;
        if (!player.isSpectator() && player.getAbilities().mayBuild) {
            ItemStack itemstack = player.getItemInHand(hand);
            EquipmentSlot equipmentSlot = getEquipmentSlotForItem(itemstack);

            Item item = itemstack.getItem();

            //special items
            if (item instanceof BannerItem ||
                    DummyMobType.get(itemstack) != DummyMobType.UNDEFINED ||
                    ForgeHelper.canEquipItem(this, itemstack, EquipmentSlot.HEAD)) {
                equipmentSlot = EquipmentSlot.HEAD;
            }

            // empty hand -> unequip
            if (itemstack.isEmpty() && hand == InteractionHand.MAIN_HAND) {
                equipmentSlot = this.getClickedSlot(vec);
                if (this.hasItemInSlot(equipmentSlot)) {
                    if (player.level.isClientSide) return InteractionResult.CONSUME;
                    this.unEquipArmor(player, equipmentSlot, hand);
                    inventoryChanged = true;

                }
            }
            // armor item in hand -> equip/swap
            else if (equipmentSlot.getType() == EquipmentSlot.Type.ARMOR) {
                if (player.level.isClientSide) return InteractionResult.CONSUME;
                this.equipArmor(player, equipmentSlot, itemstack, hand);
                inventoryChanged = true;

            }
            //remove sack
            else if (item instanceof ShearsItem) {
                if (!this.isSheared()) {
                    level.playSound(player, this, SoundEvents.GROWING_PLANT_CROP, SoundSource.BLOCKS, 1.0F, 1.0F);
                    if (player.level.isClientSide) return InteractionResult.CONSUME;
                    this.setSheared(true);
                    return InteractionResult.SUCCESS;
                }
            }


            if (inventoryChanged) {
                this.setLastArmorItem(equipmentSlot, itemstack);
                if (!this.level.isClientSide) {
                    NetworkHandler.CHANNEL.sentToAllClientPlayersTrackingEntity(this,
                            new ClientBoundSyncEquipMessage(this.getId(), equipmentSlot.getIndex(), this.getItemBySlot(equipmentSlot)));
                }
                //this.applyEquipmentModifiers();
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    private void unEquipArmor(Player player, EquipmentSlot slot, InteractionHand hand) {
        // set slot to stack which is empty stack
        ItemStack itemstack = this.getItemBySlot(slot);
        ItemStack itemstack2 = itemstack.copy();

        player.setItemInHand(hand, itemstack2);
        //clear armor
        this.setItemSlot(slot, ItemStack.EMPTY);

        //this.applyEquipmentModifiers();
        //now done here^
        this.getAttributes().removeAttributeModifiers(itemstack2.getAttributeModifiers(slot));
        //clear mob type
        if (slot == EquipmentSlot.HEAD) this.mobType = DummyMobType.UNDEFINED;

    }

    private void equipArmor(Player player, EquipmentSlot slot, ItemStack stack, InteractionHand hand) {
        ItemStack currentItem = this.getItemBySlot(slot);
        ItemStack newItem = stack.copy();
        newItem.setCount(1);

        player.setItemInHand(hand, ItemUtils.createFilledResult(stack.copy(), player, currentItem, player.isCreative()));

        this.setItemSlot(slot, newItem);

        //this.applyEquipmentModifiers();
        //now done here^
        this.getAttributes().addTransientAttributeModifiers(newItem.getAttributeModifiers(slot));
        if (slot == EquipmentSlot.HEAD) {
            this.mobType = DummyMobType.get(newItem);
        }
    }

    public boolean canScare() {
        return this.mobType == DummyMobType.SCARECROW;
    }

    public boolean canAttract() {
        return this.mobType == DummyMobType.DECOY;
    }

    private EquipmentSlot getClickedSlot(Vec3 vec3) {
        EquipmentSlot equipmentSlot = EquipmentSlot.MAINHAND;
        double d0 = vec3.y;
        EquipmentSlot slot = EquipmentSlot.FEET;
        if (d0 >= 0.1D && d0 < 0.1D + (0.45D) && this.hasItemInSlot(slot)) {
            equipmentSlot = EquipmentSlot.FEET;
        } else if (d0 >= 0.9D + (0.0D) && d0 < 0.9D + (0.7D) && this.hasItemInSlot(EquipmentSlot.CHEST)) {
            equipmentSlot = EquipmentSlot.CHEST;
        } else if (d0 >= 0.4D && d0 < 0.4D + (0.8D) && this.hasItemInSlot(EquipmentSlot.LEGS)) {
            equipmentSlot = EquipmentSlot.LEGS;
        } else if (d0 >= 1.6D && this.hasItemInSlot(EquipmentSlot.HEAD)) {
            equipmentSlot = EquipmentSlot.HEAD;
        }
        return equipmentSlot;
    }

    private void setLastArmorItem(EquipmentSlot type, ItemStack stack) {
        this.lastArmorItems.set(type.getIndex(), stack);
    }

    public void applyEquipmentModifiers() {
        //living entity code here. apparently every entity does this check every tick.
        //trying instead to run it only when needed instead
        if (!this.level.isClientSide) {
            for (EquipmentSlot equipmentSlot : EquipmentSlot.values()) {
                ItemStack itemstack;
                if (equipmentSlot.getType() == EquipmentSlot.Type.ARMOR) {
                    itemstack = this.lastArmorItems.get(equipmentSlot.getIndex());

                    ItemStack slot = this.getItemBySlot(equipmentSlot);
                    if (!ItemStack.matches(slot, itemstack)) {
                        if (!slot.equals(itemstack))
                            //packets are already handled by livingEntity detectEquipmentChange
                            //send packet
                            //Network.sendToAllTracking(this.world,this, new Network.PacketSyncEquip(this.getEntityId(), equipmentslottype.getIndex(), itemstack));
                            ForgeHelper.onEquipmentChange(this, equipmentSlot, itemstack, slot);
                        if (!itemstack.isEmpty()) {
                            this.getAttributes().removeAttributeModifiers(itemstack.getAttributeModifiers(equipmentSlot));
                        }
                        if (!slot.isEmpty()) {
                            this.getAttributes().addTransientAttributeModifiers(slot.getAttributeModifiers(equipmentSlot));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void dropEquipment() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.ARMOR) {
                continue;
            }
            ItemStack armor = getItemBySlot(slot);
            if (!armor.isEmpty()) {
                this.spawnAtLocation(armor, 1.0f);
            }
        }
    }

    public void dismantle(boolean drops) {
        if (!this.level.isClientSide && this.isAlive()) {
            if (drops) {
                this.dropEquipment();
                this.spawnAtLocation(Dummmmmmy.DUMMY_ITEM.get(), 1);
            }
            this.level.playSound(null, this.getX(), this.getY(), this.getZ(), this.getDeathSound(),
                    this.getSoundSource(), 1.0F, 1.0F);

            ((ServerLevel) this.level).sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()),
                    this.getX(), this.getY(0.6666666666666666D), this.getZ(), 10, (this.getBbWidth() / 4.0F),
                    (this.getBbHeight() / 4.0F), (this.getBbWidth() / 4.0F), 0.05D);
            this.remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void kill() {
        this.dismantle(true);
    }

    //@Override
    @PlatformOnly(PlatformOnly.FORGE)
    public ItemStack getPickedResult(HitResult target) {
        return new ItemStack(Dummmmmmy.DUMMY_ITEM.get());
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return super.isInvulnerableTo(source) || source == DamageSource.DROWN || source == DamageSource.IN_WALL;
    }

    @Override
    public boolean hurt(DamageSource source, float damage) {
        //not immune to void damage, immune to drown, wall
        if (source == DamageSource.OUT_OF_WORLD) {
            this.remove(RemovalReason.KILLED);
            return true;
        }
        //workaround for wither boss, otherwise it would keep targeting the dummy forever
        if (source.getDirectEntity() instanceof WitherBoss || source.getEntity() instanceof WitherBoss) {
            this.dismantle(true);
            return true;
        }
        // dismantling + adding players to dps message list
        if (source.getEntity() instanceof Player player) {
            if (player instanceof ServerPlayer sp) {
                currentlyAttacking.put(sp, CommonConfigs.MAX_COMBAT_INTERVAL.get());
            }
            // shift-left-click with empty hand dismantles
            if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty() && !this.unbreakable) {
                dismantle(!player.isCreative());
                return false;
            }
        }
        this.currentDamageSource = source;
        boolean result = super.hurt(source, damage);
        this.currentDamageSource = null;
        //set to 0 to disable red glow that happens when hurt
        this.hurtTime = 0;

        return result;
    }


    //all damaging stuff will inevitably call this function. intercepting to block damage and show it
    @Override
    public void setHealth(float newHealth) {
        if (newHealth == this.getMaxHealth()) {
            super.setHealth(newHealth);
        } else {

            float damage = this.getHealth() - newHealth;
            if (damage > 0) {

                //if damage is in the same tick it gets added
                if (this.lastTickActuallyDamaged != this.tickCount) {
                    this.animationPosition = 0;
                }
                this.animationPosition = Math.min(this.animationPosition + damage, 60f);
                this.lastTickActuallyDamaged = this.tickCount;

                if (!this.level.isClientSide) {
                    DamageSource actualSource = null;
                    //accounts for forge event modifying damage... I think. On fabric this isn't set yet
                    if (PlatformHelper.getPlatform().isForge()) {
                        CombatEntry currentCombatEntry = this.getCombatTracker().getLastEntry();
                        //is same damage as current one. Sanity check I guess
                        if (currentCombatEntry != null && currentCombatEntry.getTime() == this.tickCount &&
                                Mth.equal(damage, currentCombatEntry.getDamage())) {
                            actualSource = currentCombatEntry.getSource();
                        }
                    } else actualSource = currentDamageSource;

                    if (currentDamageSource != null) {
                        this.showDamageDealt(damage, DamageType.get(actualSource, this.critical));
                        this.updateTargetBlock(damage);
                    }
                    this.critical = false;
                }
            }
        }
    }

    private void showDamageDealt(float damage, DamageType type) {
        //custom update packet
        NetworkHandler.CHANNEL.sentToAllClientPlayersTrackingEntity(this,
                new ClientBoundUpdateAnimationMessage(this.getId(), this.animationPosition));

        for (var p : this.currentlyAttacking.keySet()) {
            NetworkHandler.CHANNEL.sendToClientPlayer(p,
                    new ClientBoundDamageNumberMessage(this.getId(), damage, type.ordinal()));
        }

        this.totalDamageTakenInCombat += damage;
    }

    private void updateTargetBlock(float damage) {
        if (damage <= 0) return;
        BlockPos pos = this.getOnPos();
        BlockState state = this.getBlockStateOn();
        if (state.getBlock() instanceof TargetBlock) {
            if (!level.getBlockTicks().hasScheduledTick(pos, state.getBlock())) {
                int power = (int) Mth.clamp((damage / this.getHealth()) * 15, 1, 15);
                level.setBlock(pos, state.setValue(BlockStateProperties.POWER, power), 3);
                level.scheduleTick(pos, state.getBlock(), 20);
            }
        }
    }

    @Override
    public void tick() {

        //show true damage that has bypassed hurt method
        if (lastTickActuallyDamaged + 1 == this.tickCount && !this.level.isClientSide) {
            float trueDamage = this.getMaxHealth() - this.getHealth();
            if (trueDamage > 0) {
                this.heal(trueDamage);
                this.showDamageDealt(trueDamage, DamageType.TRUE);
            }
        }

        BlockPos onPos = this.getOnPos();

        //check if on stable ground. used for automation
        if (this.level.getGameTime() % 20L == 0L && !this.level.isClientSide) {
            if (level.isEmptyBlock(onPos)) {
                this.dismantle(true);
                return;
            }

        }

        this.setNoGravity(true);
        BlockState onState = this.level.getBlockState(onPos);
        onState.getBlock().stepOn(this.level, onPos, onState, this);

        //used for fire damage, poison damage etc.
        //so you can damage it like any mob

        this.baseTick();


        this.level.getProfiler().push("travel");
        this.travel(new Vec3(this.xxa, this.yya, this.zza));
        this.level.getProfiler().pop();


        this.level.getProfiler().push("push");
        this.pushEntities();
        this.level.getProfiler().pop();
        //end living tick stuff


        if (this.level.isClientSide) {
            //set to 0 to disable red glow that happens when hurt
            this.hurtTime = 0;//this.maxHurtTime;
            this.prevShakeAmount = this.shakeAmount;
            this.prevAnimationPosition = this.animationPosition;
            //client animation
            if (this.animationPosition > 0) {

                this.shakeAmount++;
                this.animationPosition -= 0.8f;
                if (this.animationPosition <= 0) {
                    this.shakeAmount = 0;
                    this.animationPosition = 0;
                }
            }

        } else {
            // DPS!
            CombatTracker tracker = this.getCombatTracker();

            //TODO: move dps mode logic to client
            //am i being attacked?
            if (tracker.isInCombat() && this.totalDamageTakenInCombat > 0) {

                float combatDuration = tracker.getCombatDuration();
                CommonConfigs.DpsMode dpsMode = CommonConfigs.DYNAMIC_DPS.get();
                if (dpsMode != CommonConfigs.DpsMode.OFF && combatDuration > 0) {

                    boolean dynamic = dpsMode == CommonConfigs.DpsMode.DYNAMIC;
                    float seconds = combatDuration / 20f + 1;
                    float dps = totalDamageTakenInCombat / seconds;
                    List<ServerPlayer> outOfCombat = new ArrayList<>();

                    for (var e : this.currentlyAttacking.entrySet()) {
                        ServerPlayer p = e.getKey();
                        int timer = e.getValue() - 1;
                        this.currentlyAttacking.replace(p, timer);

                        boolean showMessage = dynamic && this.lastTickActuallyDamaged + 1 == this.tickCount;
                        if (timer <= 0) {
                            outOfCombat.add(p);
                            if (!dynamic) showMessage = true;
                        }
                        //here is to visually show dps on status message
                        if (showMessage && p.distanceTo(this) < 64) {
                            p.displayClientMessage(Component.translatable("message.dummmmmmy.dps",
                                    this.getDisplayName(),
                                    new DecimalFormat("#.##").format(dps)), true);

                        }
                    }

                    outOfCombat.forEach(currentlyAttacking::remove);
                }
            } else {
                this.currentlyAttacking.clear();
                this.totalDamageTakenInCombat = 0;
            }
        }
    }

    @Override
    public void setDeltaMovement(Vec3 motionIn) {
    }

    @Override
    public void knockback(double strength, double x, double z) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    protected boolean isImmobile() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void markHurt() {
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGroundIn, BlockState state, BlockPos pos) {
    }

    @Override
    public void setNoGravity(boolean ignored) {
        super.setNoGravity(true);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHitIn) {
    }

    @Override
    public SoundEvent getHurtSound(DamageSource ds) {
        return SoundEvents.ARMOR_STAND_HIT;
    }

    @Override
    public @NotNull SoundEvent getDeathSound() {
        return SoundEvents.ARMOR_STAND_BREAK;
    }

    @Override
    public @NotNull MobType getMobType() {
        return this.mobType.getType();
    }

    public static AttributeSupplier.Builder makeAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.FOLLOW_RANGE, 16.0D)
                .add(Attributes.MOVEMENT_SPEED, 0D)
                .add(Attributes.MAX_HEALTH, 40D)
                .add(Attributes.ARMOR, 0D)
                .add(Attributes.ATTACK_DAMAGE, 0D)
                .add(Attributes.FLYING_SPEED, 0D);
    }

    public void updateClientDamage(float damage, int damageType) {
        if (ClientConfigs.DAMAGE_NUMBERS.get()) {
            this.level.addParticle(Dummmmmmy.NUMBER_PARTICLE.get(),
                    this.getX(), this.getY() + 1, this.getZ(), damage, damageType, this.damageNumberPos++);
        }
    }

    public void updateAnimation(float shake) {
        this.animationPosition = shake;
    }

    public void moist() {
        this.critical = true;
    }
}

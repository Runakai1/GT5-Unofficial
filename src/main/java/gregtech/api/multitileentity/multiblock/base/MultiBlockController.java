package gregtech.api.multitileentity.multiblock.base;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.IAlignmentLimits;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.util.Vec3Impl;
import cpw.mods.fml.common.network.NetworkRegistry;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gregtech.api.enums.GT_Values;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.enums.TextureSet;
import gregtech.api.interfaces.IDescribable;
import gregtech.api.interfaces.tileentity.IMachineProgress;
import gregtech.api.multitileentity.MultiTileEntityContainer;
import gregtech.api.multitileentity.MultiTileEntityRegistry;
import gregtech.api.multitileentity.interfaces.IMultiBlockController;
import gregtech.api.multitileentity.interfaces.IMultiBlockFluidHandler;
import gregtech.api.multitileentity.interfaces.IMultiBlockInventory;
import gregtech.api.multitileentity.interfaces.IMultiTileEntity;
import gregtech.api.multitileentity.interfaces.IMultiTileEntity.IMTE_AddToolTips;
import gregtech.api.multitileentity.machine.MultiTileBasicMachine;
import gregtech.api.objects.GT_ItemStack;
import gregtech.api.util.GT_Multiblock_Tooltip_Builder;
import gregtech.api.util.GT_Utility;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.Tuple;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidTank;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static gregtech.GT_Mod.GT_FML_LOGGER;
import static gregtech.api.enums.GT_Values.NBT;

public abstract class MultiBlockController<T extends MultiBlockController<T>> extends MultiTileBasicMachine
    implements IAlignment, IConstructable, IMultiBlockController, IDescribable, IMachineProgress, IMultiBlockFluidHandler, IMultiBlockInventory, IMTE_AddToolTips
{
    private static final Map<Integer, GT_Multiblock_Tooltip_Builder> tooltip = new ConcurrentHashMap<>();

    protected BuildState buildState = new BuildState();

    // The 0th slot is the default inventory of the MultiBlock; any other has been added by an Inventory Extender of sorts
    protected List<ItemStack[]> multiBlockInventory = new ArrayList<>();


    private int mMaxProgresstime = 0, mProgresstime = 0;
    private boolean mStructureOkay = false, mStructureChanged = false;
    private boolean mWorks = true, mWorkUpdate = false, mWasShutdown = false, mActive = false;
    private ExtendedFacing mExtendedFacing = ExtendedFacing.DEFAULT;
    private IAlignmentLimits mLimits = getInitialAlignmentLimits();

    /** Registry ID of the required casing */
    public abstract short getCasingRegistryID();
    /** Meta ID of the required casing */
    public abstract short getCasingMeta();


    /**
     * Create the tooltip for this multi block controller.
     */
    protected abstract GT_Multiblock_Tooltip_Builder createTooltip();

    /**
     * @return The starting offset for the structure builder
     */
    public abstract Vec3Impl getStartingStructureOffset();

    /**
     * Due to limitation of Java type system, you might need to do an unchecked cast.
     * HOWEVER, the returned IStructureDefinition is expected to be evaluated against current instance only, and should
     * not be used against other instances, even for those of the same class.
     */
    public abstract IStructureDefinition<T> getStructureDefinition();

    /**
     * Checks the Machine.
     * <p>
     * NOTE: If using `buildState` be sure to `startBuilding()` and either `endBuilding()` or `failBuilding()`
     */
    public abstract boolean checkMachine();

    /**
     * Checks the Recipe
     */
    public abstract boolean checkRecipe(ItemStack aStack);


    @Override
    public void writeMultiTileNBT(NBTTagCompound aNBT) {
        super.writeMultiTileNBT(aNBT);

        aNBT.setBoolean(NBT.STRUCTURE_OK, mStructureOkay);
        aNBT.setByte(NBT.ROTATION, (byte) mExtendedFacing.getRotation().getIndex());
        aNBT.setByte(NBT.FLIP, (byte) mExtendedFacing.getFlip().getIndex());
    }

    @Override
    public void readMultiTileNBT(NBTTagCompound aNBT) {
        super.readMultiTileNBT(aNBT);

        // Multiblock inventories are a collection of inventories.  The first inventory is the default internal inventory,
        // and the others are added by inventory extending blocks.
        if(mInventory != null) multiBlockInventory.add(mInventory);

        mStructureOkay = aNBT.getBoolean(NBT.STRUCTURE_OK);
        mExtendedFacing = ExtendedFacing.of(
            ForgeDirection.getOrientation(getFrontFacing()),
            Rotation.byIndex(aNBT.getByte(NBT.ROTATION)),
            Flip.byIndex(aNBT.getByte(NBT.FLIP))
        );
    }

    @Override
    public void addToolTips(List<String> aList, ItemStack aStack, boolean aF3_H) {
        aList.addAll(Arrays.asList(getDescription()));
    }

    @Override
    public String[] getDescription() {
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) {
            return getTooltip().getStructureInformation();
        } else {
            return getTooltip().getInformation();
        }
    }

    @Override
    protected void addDebugInfo(EntityPlayer aPlayer, int aLogLevel, ArrayList<String> tList) {
        tList.add("Structure ok: " + checkStructure(false));
    }

    protected int getToolTipID() {
        return getMultiTileEntityRegistryID() << 16 + getMultiTileEntityID();
    }

    protected GT_Multiblock_Tooltip_Builder getTooltip() {
        return createTooltip();
//        final int tooltipId = getToolTipID();
//        final GT_Multiblock_Tooltip_Builder tt = tooltip.get(tooltipId);
//        if (tt == null) {
//            return tooltip.computeIfAbsent(tooltipId, k -> createTooltip());
//        }
//        return tt;
    }


    @Override
    public boolean checkStructure(boolean aForceReset) {
        if(!isServerSide()) return mStructureOkay;

        // Only trigger an update if forced (from onPostTick, generally), or if the structure has changed
        if ((mStructureChanged || aForceReset)) {
            mStructureOkay = checkMachine();
        }
        mStructureChanged = false;
        return mStructureOkay;
    }

    @Override
    public void onStructureChange() {
        mStructureChanged = true;
    }

    public final boolean checkPiece(String piece, Vec3Impl offset) {
        return checkPiece(piece, offset.get0(), offset.get1(), offset.get2());
    }

    /**
     * Explanation of the world coordinate these offset means:
     * <p>
     * Imagine you stand in front of the controller, with controller facing towards you not rotated or flipped.
     * <p>
     * The horizontalOffset would be the number of blocks on the left side of the controller, not counting controller itself.
     * The verticalOffset would be the number of blocks on the top side of the controller, not counting controller itself.
     * The depthOffset would be the number of blocks between you and controller, not counting controller itself.
     * <p>
     * All these offsets can be negative.
     */
    protected final boolean checkPiece(String piece, int horizontalOffset, int verticalOffset, int depthOffset) {
        return getCastedStructureDefinition().check(
            this, piece, getWorld(), getExtendedFacing(), getXCoord(), getYCoord(), getZCoord(), horizontalOffset, verticalOffset,
            depthOffset, !mStructureOkay
        );
    }

    public final boolean buildPiece(String piece, ItemStack trigger, boolean hintsOnly, Vec3Impl offset) {
        return buildPiece(piece, trigger, hintsOnly, offset.get0(), offset.get1(), offset.get2());
    }

    protected final boolean buildPiece(String piece, ItemStack trigger, boolean hintOnly, int horizontalOffset, int verticalOffset, int depthOffset) {
        return getCastedStructureDefinition().buildOrHints(
            this, trigger, piece, getWorld(), getExtendedFacing(), getXCoord(), getYCoord(), getZCoord(), horizontalOffset,
            verticalOffset, depthOffset, hintOnly
        );
    }

    @SuppressWarnings("unchecked")
    private IStructureDefinition<MultiBlockController<T>> getCastedStructureDefinition() {
        return (IStructureDefinition<MultiBlockController<T>>) getStructureDefinition();
    }

    @Override
    public ExtendedFacing getExtendedFacing() {
        return mExtendedFacing;
    }

    @Override
    public void setExtendedFacing(ExtendedFacing newExtendedFacing) {
        if (mExtendedFacing != newExtendedFacing) {
            onStructureChange();
            if (mStructureOkay)
                stopMachine();
            mExtendedFacing = newExtendedFacing;
            mStructureOkay = false;
            if (isServerSide()) {
                StructureLibAPI.sendAlignment(
                    this, new NetworkRegistry.TargetPoint(getWorld().provider.dimensionId, getXCoord(), getYCoord(), getZCoord(), 512));
            } else {
                issueTextureUpdate();
            }
        }
    }

    @Override
    public boolean onWrenchRightClick(EntityPlayer aPlayer, ItemStack tCurrentItem, byte wrenchSide, float aX, float aY, float aZ) {
        if (wrenchSide != getFrontFacing())
            return super.onWrenchRightClick(aPlayer, tCurrentItem, wrenchSide, aX, aY, aZ);
        if (aPlayer.isSneaking()) {
            // we won't be allowing horizontal flips, as it can be perfectly emulated by rotating twice and flipping horizontally
            // allowing an extra round of flip make it hard to draw meaningful flip markers in GT_Proxy#drawGrid
            toolSetFlip(getFlip().isHorizontallyFlipped() ? Flip.NONE : Flip.HORIZONTAL);
        } else {
            toolSetRotation(null);
        }
        return true;
    }

    @Override
    public void onFirstTick(boolean aIsServerSide) {
        super.onFirstTick(aIsServerSide);
        if (aIsServerSide)
            checkStructure(true);
        else
            StructureLibAPI.queryAlignment(this);
    }

    @Override
    public void onPostTick(long aTick, boolean aIsServerSide) {
        if (aIsServerSide) {
            if (aTick % 600 == 5) {
                // Recheck the structure every 30 seconds or so
                if (!checkStructure(false)) checkStructure(true);
            }
        }
    }

    @Override
    public final boolean isFacingValid(byte aFacing) {
        return canSetToDirectionAny(ForgeDirection.getOrientation(aFacing));
    }

    @Override
    public void onFacingChange() {
        toolSetDirection(ForgeDirection.getOrientation(getFrontFacing()));
        onStructureChange();
    }

    @Override
    public boolean allowCoverOnSide(byte aSide, GT_ItemStack aCoverID) {
        return aSide != mFacing;
    }

    @Override
    public String[] getStructureDescription(ItemStack stackSize) {
        return getTooltip().getStructureHint();
    }

    @Override
    public IAlignmentLimits getAlignmentLimits() {
        return mLimits;
    }

    protected void setAlignmentLimits(IAlignmentLimits mLimits) {
        this.mLimits = mLimits;
    }

    // IMachineProgress
    @Override
    public int getProgress() {
        return mProgresstime;
    }

    @Override
    public int getMaxProgress() {
        return mMaxProgresstime;
    }

    @Override
    public boolean increaseProgress(int aProgressAmountInTicks) {
        return increaseProgressGetOverflow(aProgressAmountInTicks) != aProgressAmountInTicks;
    }

    @Override
    public FluidStack getDrainableFluid(byte aSide) {
        final IFluidTank tank = getFluidTankDrainable(aSide, null);
        return tank ==  null ? null : tank.getFluid();

    }

    /**
     * Increases the Progress, returns the overflown Progress.
     */
    public int increaseProgressGetOverflow(int aProgress) {
        return 0;
    }

    @Override
    public boolean hasThingsToDo() {
        return getMaxProgress() > 0;
    }

    @Override
    public boolean hasWorkJustBeenEnabled() {
        return mWorkUpdate;
    }

    @Override
    public void enableWorking() {
        if (!mWorks) mWorkUpdate = true;
        mWorks = true;
        mWasShutdown = false;
    }

    @Override
    public void disableWorking() {
        mWorks = false;
    }

    @Override
    public boolean isAllowedToWork() {
        return mWorks;
    }

    @Override
    public boolean isActive() {
        return mActive;
    }

    @Override
    public void setActive(boolean aActive) {
        mActive = aActive;
    }

    @Override
    public boolean wasShutdown() {
        return mWasShutdown;
    }

    // End IMachineProgress

    public void stopMachine() {
        disableWorking();
    }

    protected IAlignmentLimits getInitialAlignmentLimits() {
        return (d, r, f) -> !f.isVerticallyFliped();
    }

    public static class BuildState {
        /**
         * Utility class to keep track of the build state of a multiblock
         */
        boolean building = false;
        Vec3Impl currentOffset;

        public void startBuilding(Vec3Impl structureOffset) {
            if (building) throw new IllegalStateException("Already building!");
            building = true;
            setCurrentOffset(structureOffset);
        }

        public Vec3Impl setCurrentOffset(Vec3Impl structureOffset) {
            verifyBuilding();
            return (currentOffset = structureOffset);
        }

        private void verifyBuilding() {
            if (!building) throw new IllegalStateException("Not building!");
        }

        public boolean failBuilding() {
            building = false;
            currentOffset = null;
            return false;
        }

        public Vec3Impl stopBuilding() {
            final Vec3Impl toReturn = getCurrentOffset();
            building = false;
            currentOffset = null;

            return toReturn;
        }

        public Vec3Impl getCurrentOffset() {
            verifyBuilding();
            return currentOffset;
        }

        public Vec3Impl addOffset(Vec3Impl offset) {
            verifyBuilding();
            return setCurrentOffset(currentOffset.add(offset));
        }
    }

    public <S> IStructureElement<S> addMultiTileCasing(int aRegistryID, int aBlockMeta, int aModes) {
        return new IStructureElement<S>() {
            private final short[] DEFAULT = new short[]{255, 255, 255, 0};
            private IIcon[] mIcons = null;

            @Override
            public boolean check(S t, World world, int x, int y, int z) {
                final TileEntity tileEntity = world.getTileEntity(x, y, z);
                if (!(tileEntity instanceof MultiBlockPart)) return false;

                final MultiBlockPart part = (MultiBlockPart) tileEntity;
                if (aRegistryID != part.getMultiTileEntityRegistryID() || aBlockMeta != part.getMultiTileEntityID()) return false;

                final IMultiBlockController tTarget = part.getTarget(false);
                if (tTarget != null && tTarget != MultiBlockController.this) return false;

                part.setTarget(MultiBlockController.this, aModes);
                return true;
            }

            @Override
            public boolean spawnHint(S t, World world, int x, int y, int z, ItemStack trigger) {
                if (mIcons == null) {
                    mIcons = new IIcon[6];
                    Arrays.fill(mIcons, TextureSet.SET_NONE.mTextures[OrePrefixes.block.mTextureIndex].getIcon());
//                    Arrays.fill(mIcons, getTexture(aCasing);
//                    for (int i = 0; i < 6; i++) {
//                        mIcons[i] = aCasing.getIcon(i, aMeta);
//                    }
                }
                final short[] RGBA = DEFAULT;
                StructureLibAPI.hintParticleTinted(world, x, y, z, mIcons, RGBA);
//                StructureLibAPI.hintParticle(world, x, y, z, aCasing, aMeta);
                return true;
            }

            @Override
            public boolean placeBlock(S t, World world, int x, int y, int z, ItemStack trigger) {
                final MultiTileEntityRegistry tRegistry = MultiTileEntityRegistry.getRegistry(aRegistryID);
                final MultiTileEntityContainer tContainer = tRegistry.getNewTileEntityContainer(world, x, y, z, aBlockMeta, null);
                if(tContainer == null) {
                    GT_FML_LOGGER.error("NULL CONTAINER");
                    return false;
                }
                final IMultiTileEntity te = ((IMultiTileEntity)tContainer.mTileEntity);
                if(!(te instanceof MultiBlockPart)) {
                    GT_FML_LOGGER.error("Not a multiblock part");
                    return false;
                }
                if (world.setBlock(x, y, z, tContainer.mBlock, 15 - tContainer.mBlockMetaData, 2)) {
                    tContainer.setMultiTile(world, x, y, z);
                    ((MultiBlockPart) te).setTarget(MultiBlockController.this, aModes);
                }

                return false;
            }

            public IIcon getTexture(OrePrefixes aBlock) {
                return TextureSet.SET_NONE.mTextures[OrePrefixes.block.mTextureIndex].getIcon();
            }
        };
    }

    /**
     * Fluid - MultiBlock related Fluid Tank behaviour.
     */

    protected IFluidTank getFluidTankFillable(MultiBlockPart aPart, byte aSide, FluidStack aFluidToFill) {return getFluidTankFillable(aSide, aFluidToFill);}
    protected IFluidTank getFluidTankDrainable(MultiBlockPart aPart, byte aSide, FluidStack aFluidToDrain) {return getFluidTankDrainable(aSide, aFluidToDrain);}
    protected IFluidTank[] getFluidTanks(MultiBlockPart aPart, byte aSide) {return getFluidTanks(aSide);}

    @Override
    public int fill(MultiBlockPart aPart, ForgeDirection aDirection, FluidStack aFluid, boolean aDoFill) {
        if (aFluid == null || aFluid.amount <= 0) return 0;
        final IFluidTank tTank = getFluidTankFillable(aPart, (byte)aDirection.ordinal(), aFluid);
        if (tTank == null) return 0;
        final int rFilledAmount = tTank.fill(aFluid, aDoFill);
        if (rFilledAmount > 0 && aDoFill) mInventoryChanged = true;
        return rFilledAmount;
    }

    @Override
    public FluidStack drain(MultiBlockPart aPart, ForgeDirection aDirection, FluidStack aFluid, boolean aDoDrain) {
        if (aFluid == null || aFluid.amount <= 0) return null;
        final IFluidTank tTank = getFluidTankDrainable(aPart, (byte)aDirection.ordinal(), aFluid);
        if (tTank == null || tTank.getFluid() == null || tTank.getFluidAmount() == 0 || !tTank.getFluid().isFluidEqual(aFluid)) return null;
        final FluidStack rDrained = tTank.drain(aFluid.amount, aDoDrain);
        if (rDrained != null && aDoDrain) markInventoryBeenModified();
        return rDrained;
    }

    @Override
    public FluidStack drain(MultiBlockPart aPart, ForgeDirection aDirection, int aAmountToDrain, boolean aDoDrain) {
        if (aAmountToDrain <= 0) return null;
        final IFluidTank tTank = getFluidTankDrainable(aPart, (byte)aDirection.ordinal(), null);
        if (tTank == null || tTank.getFluid() == null || tTank.getFluidAmount() == 0) return null;
        final FluidStack rDrained = tTank.drain(aAmountToDrain, aDoDrain);
        if (rDrained != null && aDoDrain) markInventoryBeenModified();
        return rDrained;
    }

    @Override
    public boolean canFill(MultiBlockPart aPart, ForgeDirection aDirection, Fluid aFluid) {
        if (aFluid == null) return false;
        final IFluidTank tTank = getFluidTankFillable(aPart, (byte)aDirection.ordinal(), new FluidStack(aFluid, 0));
        return tTank != null && (tTank.getFluid() == null || tTank.getFluid().getFluid() == aFluid);
    }

    @Override
    public boolean canDrain(MultiBlockPart aPart, ForgeDirection aDirection, Fluid aFluid) {
        if (aFluid == null) return false;
        final IFluidTank tTank = getFluidTankDrainable(aPart, (byte)aDirection.ordinal(), new FluidStack(aFluid, 0));
        return tTank != null && (tTank.getFluid() != null && tTank.getFluid().getFluid() == aFluid);
    }

    @Override
    public FluidTankInfo[] getTankInfo(MultiBlockPart aPart, ForgeDirection aDirection) {
        final IFluidTank[] tTanks = getFluidTanks(aPart, (byte)aDirection.ordinal());
        if (tTanks == null || tTanks.length <= 0) return GT_Values.emptyFluidTankInfo;
        final FluidTankInfo[] rInfo = new FluidTankInfo[tTanks.length];
        for (int i = 0; i < tTanks.length; i++) rInfo[i] = new FluidTankInfo(tTanks[i]);
        return rInfo;
    }

    /**
     * Energy - MultiBlock related Energy behavior
     */

    @Override
    public boolean isUniversalEnergyStored(MultiBlockPart aPart, long aEnergyAmount) {
        return getUniversalEnergyStored(aPart) >= aEnergyAmount;
    }

    @Override
    public long getUniversalEnergyStored(MultiBlockPart aPart) {
        return Math.min(getUniversalEnergyStored(), getUniversalEnergyCapacity());
    }

    @Override
    public long getUniversalEnergyCapacity(MultiBlockPart aPart) {
        return getUniversalEnergyCapacity();
    }

    @Override
    public long getOutputAmperage(MultiBlockPart aPart) {
        return getOutputAmperage();
    }

    @Override
    public long getOutputVoltage(MultiBlockPart aPart) {
        return getOutputVoltage();
    }

    @Override
    public long getInputAmperage(MultiBlockPart aPart) {
        return getInputAmperage();
    }

    @Override
    public long getInputVoltage(MultiBlockPart aPart) {
        return getInputVoltage();
    }

    @Override
    public boolean decreaseStoredEnergyUnits(MultiBlockPart aPart, long aEnergy, boolean aIgnoreTooLittleEnergy) {
        return decreaseStoredEnergyUnits(aEnergy, aIgnoreTooLittleEnergy);
    }

    @Override
    public boolean increaseStoredEnergyUnits(MultiBlockPart aPart, long aEnergy, boolean aIgnoreTooMuchEnergy) {
        return increaseStoredEnergyUnits(aEnergy, aIgnoreTooMuchEnergy);
    }

    @Override
    public boolean drainEnergyUnits(MultiBlockPart aPart, byte aSide, long aVoltage, long aAmperage) {
        return drainEnergyUnits(aSide, aVoltage, aAmperage);
    }

    @Override
    public long injectEnergyUnits(MultiBlockPart aPart, byte aSide, long aVoltage, long aAmperage) {
        return injectEnergyUnits(aSide, aVoltage, aAmperage);
    }

    @Override
    public long getAverageElectricInput(MultiBlockPart aPart) {
        return getAverageElectricInput();
    }

    @Override
    public long getAverageElectricOutput(MultiBlockPart aPart) {
        return getAverageElectricOutput();
    }

    @Override
    public long getStoredEU(MultiBlockPart aPart) {
        return getStoredEU();
    }

    @Override
    public long getEUCapacity(MultiBlockPart aPart) {
        return getEUCapacity();
    }

    @Override
    public boolean inputEnergyFrom(MultiBlockPart aPart, byte aSide) {
        if (aSide == GT_Values.SIDE_UNKNOWN) return true;
        if (aSide >= 0 && aSide < 6) {
            if(isInvalid()) return false;
            if (isEnetInput()) return isEnergyInputSide(aSide);
        }
        return false;
    }

    @Override
    public boolean outputsEnergyTo(MultiBlockPart aPart, byte aSide) {
        if (aSide == GT_Values.SIDE_UNKNOWN) return true;
        if (aSide >= 0 && aSide < 6) {
            if (isInvalid()) return false;
            if (isEnetOutput()) return isEnergyOutputSide(aSide);
        }
        return false;
    }

    /**
     * Item - MultiBlock related Item behaviour.
     */


    @Override
    public boolean hasInventoryBeenModified(MultiBlockPart aPart) {
        // TODO: MultiInventory - Figure this out based on locked & the part
        return hasInventoryBeenModified();
    }

    @Override
    public boolean isValidSlot(MultiBlockPart aPart, int aIndex) {
        return false;
    }

    @Override
    public boolean addStackToSlot(MultiBlockPart aPart, int aIndex, ItemStack aStack) {
        return false;
    }

    @Override
    public boolean addStackToSlot(MultiBlockPart aPart, int aIndex, ItemStack aStack, int aAmount) {
        return false;
    }

    protected Pair<ItemStack[], Integer> getInventory(int lockedInventory, int aSlot) {
        if (lockedInventory != -1) return new ImmutablePair<>(multiBlockInventory.get(lockedInventory), aSlot);

        int start = 0;
        for(ItemStack[] inv : multiBlockInventory) {
            if (aSlot > start && aSlot < start + inv.length) {
                return new ImmutablePair<>(inv, aSlot - start);
            }
            start += inv.length;
        }
        return null;
    }

    @Override
    public int[] getAccessibleSlotsFromSide(MultiBlockPart aPart, byte aSide) {
        final TIntList tList = new TIntArrayList();
        final int lockedInventory = aPart.getLockedInventory();

        int start = 0;
        if(lockedInventory == -1) {
            for (ItemStack[] inv : multiBlockInventory) {
                for (int i = start; i < inv.length + start; i++) tList.add(i);
                start += inv.length;
            }
        } else {
            final int len = multiBlockInventory.get(lockedInventory).length;
            for(int i = 0; i < len ; i++) tList.add(i);
        }
        return tList.toArray();
    }


    @Override
    public boolean canInsertItem(MultiBlockPart aPart, int aSlot, ItemStack aStack, byte aSide) {
        final int lockedInventory = aPart.getLockedInventory(), tSlot;
        final ItemStack[] inv;
        if(lockedInventory == -1) {
            final Pair<ItemStack[], Integer> tInv = getInventory(lockedInventory, aSlot);
            if(tInv == null) return false;
            inv = tInv.getLeft();
            tSlot = tInv.getRight();
        } else {
            inv = multiBlockInventory.get(lockedInventory);
            tSlot = aSlot;
        }
        return inv[tSlot] == null || GT_Utility.areStacksEqual(aStack, inv[tSlot]); //&& allowPutStack(getBaseMetaTileEntity(), aIndex, (byte) aSide, aStack)
    }

    @Override
    public boolean canExtractItem(MultiBlockPart aPart, int aSlot, ItemStack aStack, byte aSide) {
        final int lockedInventory = aPart.getLockedInventory(), tSlot;
        final ItemStack[] inv;
        if(lockedInventory == -1) {
            final Pair<ItemStack[], Integer> tInv = getInventory(lockedInventory, aSlot);
            if(tInv == null) return false;
            inv = tInv.getLeft();
            tSlot = tInv.getRight();
        } else {
            inv = multiBlockInventory.get(lockedInventory);
            tSlot = aSlot;
        }
        return inv[tSlot] != null; // && allowPullStack(getBaseMetaTileEntity(), aIndex, (byte) aSide, aStack);
    }

    @Override
    public int getSizeInventory(MultiBlockPart aPart) {
        final int lockedInventory = aPart.getLockedInventory();
        if(lockedInventory == -1) {
            int len = 0;
            for (ItemStack[] inv : multiBlockInventory) len += inv.length;
            return len;
        } else {
            return multiBlockInventory.get(lockedInventory).length;
        }
    }

    @Override
    public ItemStack getStackInSlot(MultiBlockPart aPart, int aSlot) {
        final int lockedInventory = aPart.getLockedInventory(), tSlot;
        final ItemStack[] inv;
        if(lockedInventory == -1) {
            final Pair<ItemStack[], Integer> tInv = getInventory(lockedInventory, aSlot);
            if(tInv == null) return null;
            inv = tInv.getLeft();
            tSlot = tInv.getRight();
        } else {
            inv = multiBlockInventory.get(lockedInventory);
            tSlot = aSlot;
        }
        return inv[tSlot];
    }

    @Override
    public ItemStack decrStackSize(MultiBlockPart aPart, int aSlot, int aDecrement) {
        final ItemStack tStack = getStackInSlot(aPart, aSlot);
        ItemStack rStack = GT_Utility.copyOrNull(tStack);
        if (tStack != null) {
            if (tStack.stackSize <= aDecrement) {
                setInventorySlotContents(aSlot, null);
            } else {
                rStack = tStack.splitStack(aDecrement);
                if (tStack.stackSize == 0)
                    setInventorySlotContents(aSlot, null);
            }
        }
        return rStack;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(MultiBlockPart aPart, int aSlot) {
        final Pair<ItemStack[], Integer> tInv = getInventory(aPart.getLockedInventory(), aSlot);
        if (tInv == null) return null;

        final ItemStack[] inv = tInv.getLeft();
        final int tSlot = tInv.getRight();

        final ItemStack rStack = inv[tSlot];
        inv[tSlot] = null;
        return rStack;
    }

    @Override
    public void setInventorySlotContents(MultiBlockPart aPart, int aSlot, ItemStack aStack) {
        final Pair<ItemStack[], Integer> tInv = getInventory(aPart.getLockedInventory(), aSlot);
        if (tInv == null) return;

        final ItemStack[] inv = tInv.getLeft();
        final int tSlot = tInv.getRight();
        inv[tSlot] = aStack;
    }

    @Override
    public String getInventoryName(MultiBlockPart aPart) {
        return getInventoryName(); // TODO: MultiInventory: Include part Name?
    }

    @Override
    public boolean hasCustomInventoryName(MultiBlockPart aPart) {
        return hasCustomInventoryName();
    }

    @Override
    public int getInventoryStackLimit(MultiBlockPart aPart) {
        return getInventoryStackLimit();
    }

    @Override
    public void markDirty(MultiBlockPart aPart) {
        // TODO: MultiInventory - Consider the part?
        markDirty(); markInventoryBeenModified();
    }

    @Override
    public boolean isUseableByPlayer(MultiBlockPart aPart, EntityPlayer aPlayer) {
        return isUseableByPlayer(aPlayer);
    }

    @Override
    public void openInventory(MultiBlockPart aPart) {
        // TODO: MultiInventory - consider the part's inventory
        openInventory();
    }

    @Override
    public void closeInventory(MultiBlockPart aPart) {
        // TODO: MultiInventory - consider the part's inventory
        closeInventory();
    }

    @Override
    public boolean isItemValidForSlot(MultiBlockPart aPart, int aSlot, ItemStack aStack) {
        return isItemValidForSlot(aSlot, aStack);
    }
}

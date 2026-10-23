package ru.animeoncraft.integration.neoforge;

import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Called only by the guarded disposable-world probe, with Create installed. */
final class CreateProtectionProbe {
    private CreateProtectionProbe() {}
    static void verify(ServerLevel level,Territory region,Territory other)throws Exception{
        var foreignSource=new BlockPos(region.minX()-1,120,region.minZ()+8);
        var foreignTarget=foreignSource.east();
        level.setBlockAndUpdate(foreignTarget,Blocks.STONE.defaultBlockState());
        var foreign=new BearingContraption(false,Direction.EAST);
        if(foreign.assemble(level,foreignSource))throw new IllegalStateException("Create assembled blocks from a foreign claim");
        if(!level.getBlockState(foreignTarget).is(Blocks.STONE))throw new IllegalStateException("Denied assembly changed protected terrain");
        var ownSource=foreignSource.offset(4,0,0);var ownTarget=ownSource.east();
        level.setBlockAndUpdate(ownTarget,Blocks.STONE.defaultBlockState());
        var own=new BearingContraption(false,Direction.EAST);
        if(!own.assemble(level,ownSource)||own.getBlocks().isEmpty())throw new IllegalStateException("Create assembly inside one claim failed");
        own.removeBlocksFromWorld(level,BlockPos.ZERO);
        if(!level.getBlockState(ownTarget).isAir())throw new IllegalStateException("Allowed Create assembly did not remove its block");
        var moving=com.simibubi.create.content.contraptions.OrientedContraptionEntity.create(level,own,Direction.EAST);
        moving.setPos(other.minX()+8,120,other.minZ()+8);
        if(((ContraptionPermission)moving).animeoncraft$canDisassemble())throw new IllegalStateException("Foreign Create disassembly was permitted");
        moving.disassemble();
        if(moving.isRemoved()||own.getBlocks().isEmpty())throw new IllegalStateException("Denied disassembly lost its contraption");
        inventoryAndFluidBoundary(level,region);
        cannonAndHoseBoundary(level,region);
        if(Boolean.getBoolean("animeoncraft.createLifecycleProbe"))CreateLifecycleProbe.verify(level,region,other);
        System.out.println("AOC_CREATE_PROBE_PASS assembly_boundary=true own_assembly=true foreign_disassembly_blocked=true contraption_preserved=true");
    }
    private static void inventoryAndFluidBoundary(ServerLevel level,Territory region)throws Exception{
        var chest=new BlockPos(region.minX(),140,region.minZ()+8);
        level.setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());
        for(boolean outside:java.util.List.of(true,false)){
            var source=outside?chest.west():chest.east();var direction=outside?Direction.EAST:Direction.WEST;
            var funnel=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:andesite_funnel")).defaultBlockState();
            if(funnel.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING))funnel=funnel.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,direction);
            level.setBlockAndUpdate(source,funnel);
            var entity=(com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(source);
            if(entity==null)throw new IllegalStateException("Create funnel fixture unavailable");
            var behaviour=new com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour(entity,
                (world,pos,state)->new net.createmod.catnip.math.BlockFace(pos,direction));
            behaviour.findNewCapability();
            var field=com.simibubi.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase.class.getDeclaredField("targetCapability");field.setAccessible(true);
            if(field.get(behaviour)==null)throw new IllegalStateException("Vanilla chest capability unavailable in fixture");
            if((behaviour.getInventory()==null)!=outside)throw new IllegalStateException("Create inventory boundary check failed");
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var network=new com.simibubi.create.content.fluids.FluidNetwork(level,new net.createmod.catnip.math.BlockFace(source,direction),()->{calls.incrementAndGet();throw new ProbeReached();});
            for(int i=0;i<20;i++)try{network.tick();}catch(ProbeReached expected){ }
            if((calls.get()==0)!=outside)throw new IllegalStateException("Create fluid boundary check failed");
        }
        System.out.println("AOC_CREATE_TRANSFER_PROBE_PASS inventory_boundary=true own_inventory=true fluid_boundary=true own_fluid=true");
    }
    private static final class ProbeReached extends RuntimeException { private static final long serialVersionUID=1L; }

    private static void cannonAndHoseBoundary(ServerLevel level,Territory region)throws Exception{
        var source=new BlockPos(region.minX()-1,170,region.minZ()+12);var foreign=source.east();var neutral=source.west();
        var cannonBlock=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:schematicannon"));
        level.setBlockAndUpdate(source,cannonBlock.defaultBlockState());var cannon=level.getBlockEntity(source);
        var should=cannon.getClass().getDeclaredMethod("shouldPlace",BlockPos.class,net.minecraft.world.level.block.state.BlockState.class,net.minecraft.world.level.block.entity.BlockEntity.class,net.minecraft.world.level.block.state.BlockState.class,net.minecraft.world.level.block.state.BlockState.class,boolean.class);should.setAccessible(true);
        if((Boolean)should.invoke(cannon,foreign,Blocks.STONE.defaultBlockState(),null,Blocks.AIR.defaultBlockState(),Blocks.AIR.defaultBlockState(),false))throw new IllegalStateException("Schematicannon accepted a foreign target");
        var hoseBlock=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:hose_pulley"));
        level.setBlockAndUpdate(source,hoseBlock.defaultBlockState());var hose=(com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(source);
        var filler=new com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour(hose);
        var space=filler.getClass().getDeclaredMethod("getAtPos",net.minecraft.world.level.Level.class,BlockPos.class,net.minecraft.world.level.material.Fluid.class);space.setAccessible(true);
        if(!((Enum<?>)space.invoke(filler,level,foreign,net.minecraft.world.level.material.Fluids.WATER)).name().equals("BLOCKING"))throw new IllegalStateException("Hose selected foreign empty space");
        if(((Enum<?>)space.invoke(filler,level,neutral,net.minecraft.world.level.material.Fluids.WATER)).name().equals("BLOCKING"))throw new IllegalStateException("Hose could not select neutral empty space");
        var drainer=new com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour(hose);
        var classify=drainer.getClass().getDeclaredMethod("canPullFluidsFrom",net.minecraft.world.level.block.state.BlockState.class,BlockPos.class);classify.setAccessible(true);
        level.setBlockAndUpdate(foreign,Blocks.WATER.defaultBlockState());
        if(!((Enum<?>)classify.invoke(drainer,Blocks.WATER.defaultBlockState(),foreign)).name().equals("NONE"))throw new IllegalStateException("Hose selected a foreign source");
        if(((Enum<?>)classify.invoke(drainer,Blocks.WATER.defaultBlockState(),neutral)).name().equals("NONE"))throw new IllegalStateException("Hose could not select neutral source");
        System.out.println("AOC_CREATE_WORLD_PROBE_PASS cannon_foreign=false hose_foreign=false hose_neutral=true");
    }

}


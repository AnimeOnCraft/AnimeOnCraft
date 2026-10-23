package ru.animeoncraft.integration.neoforge;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Real Create methods, called exclusively by the guarded loopback disposable-world probe. */
final class CreateLifecycleProbe {
    private CreateLifecycleProbe() {}
    static void verify(ServerLevel level,Territory own,Territory foreign){
        var start=new BlockPos(own.minX()+12,160,own.minZ()+12);
        var destination=new BlockPos(foreign.minX()+12,160,foreign.minZ()+12);
        var bogeyBlock=(AbstractBogeyBlock<?>)BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:small_bogey"));
        var carriage=new Carriage(new CarriageBogey(bogeyBlock,false,new CompoundTag()),null,0);
        carriage.storage.initialize();
        var contraption=new CarriageContraption(Direction.EAST);
        contraption.anchor=start;
        contraption.bounds=new net.minecraft.world.phys.AABB(0,0,0,1,1,1);
        var local=BlockPos.ZERO;
        contraption.getBlocks().put(local,new StructureBlockInfo(local,Blocks.STONE.defaultBlockState(),null));
        var entity=CarriageContraptionEntity.create(level,contraption);
        entity.setPos(start.getX(),start.getY(),start.getZ());
        carriage.getDimensional(level).entity=new java.lang.ref.WeakReference<>(entity);
        var train=new Train(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),new TrackGraph(),new java.util.ArrayList<>(java.util.List.of(carriage)),new java.util.ArrayList<>(),false,0);
        carriage.setTrain(train);
        entity.setCarriage(carriage);
        if(!train.canDisassemble())throw new IllegalStateException("Train own-position positive control failed");
        entity.setPos(destination.getX(),destination.getY(),destination.getZ());
        if(train.canDisassemble()||train.disassemble(Direction.EAST,destination))throw new IllegalStateException("Train foreign-position disassembly was accepted");
        if(entity.isRemoved()||contraption.getBlocks().isEmpty()||carriage.anyAvailableEntity()!=entity)throw new IllegalStateException("Denied train disassembly lost its carriage");
        entity.setPos(start.getX(),start.getY(),start.getZ());
        var before=entity.position();
        boolean accepted=train.disassemble(Direction.EAST,destination);
        if(accepted||!entity.position().equals(before)||entity.isRemoved()||contraption.getBlocks().isEmpty()){
            throw new IllegalStateException("Train station destination crossed a claim: accepted="+accepted+" moved="+!entity.position().equals(before)+" removed="+entity.isRemoved());
        }
        if(!train.disassemble(Direction.EAST,start)||!entity.isRemoved())throw new IllegalStateException("Train own-station positive control failed");
        for(boolean backwards:java.util.List.of(false,true))multiCarriage(level,own,foreign,bogeyBlock,backwards);
        CreateControllerProbe.verify(level,own,foreign);
        System.out.println("AOC_CREATE_LIFECYCLE_PROBE_PASS train_foreign=false train_destination_foreign=false carriage_preserved=true own_train_disassembly=true multi_carriage_atomic=true backwards=true");
    }

    private static void multiCarriage(ServerLevel level,Territory own,Territory foreign,AbstractBogeyBlock<?> bogeyBlock,boolean backwards){
        var carriages=new java.util.ArrayList<Carriage>();
        var entities=new java.util.ArrayList<CarriageContraptionEntity>();
        for(int i=0;i<2;i++){
            var start=new BlockPos(own.minX()+16+i*4,180,own.minZ()+24+(backwards?8:0));
            var carriage=new Carriage(new CarriageBogey(bogeyBlock,false,new CompoundTag()),null,2);
            carriage.storage.initialize();
            var contraption=new CarriageContraption(Direction.EAST);contraption.anchor=start;
            contraption.bounds=new net.minecraft.world.phys.AABB(0,0,0,1,1,1);
            contraption.getBlocks().put(BlockPos.ZERO,new StructureBlockInfo(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),null));
            var entity=CarriageContraptionEntity.create(level,contraption);entity.setPos(start.getX(),start.getY(),start.getZ());
            carriage.getDimensional(level).entity=new java.lang.ref.WeakReference<>(entity);
            carriages.add(carriage);entities.add(entity);
        }
        var train=new Train(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),new TrackGraph(),carriages,new java.util.ArrayList<>(java.util.List.of(1)),false,0);
        train.currentlyBackwards=backwards;
        for(int i=0;i<carriages.size();i++){carriages.get(i).setTrain(train);entities.get(i).setCarriage(carriages.get(i));}
        if(!train.canDisassemble())throw new IllegalStateException("Multi-carriage positive control failed");
        var before=entities.stream().map(e->e.position()).toList();
        // The first destination is neutral and allowed; only the second enters the foreign claim.
        // The whole operation must be rejected before even the first carriage is moved.
        var station=new BlockPos(foreign.minX()-(backwards?5:3),180,foreign.minZ()+24);
        if(train.disassemble(Direction.EAST,station))throw new IllegalStateException("Multi-carriage foreign destination was accepted");
        for(int i=0;i<entities.size();i++)if(entities.get(i).isRemoved()||!entities.get(i).position().equals(before.get(i)))throw new IllegalStateException("Partial train disassembly changed a carriage");
        var ownStation=new BlockPos(own.minX()+32,180,own.minZ()+32+(backwards?8:0));
        if(!train.disassemble(Direction.EAST,ownStation)||entities.stream().anyMatch(e->!e.isRemoved()))throw new IllegalStateException("Multi-carriage own disassembly failed");
    }
}

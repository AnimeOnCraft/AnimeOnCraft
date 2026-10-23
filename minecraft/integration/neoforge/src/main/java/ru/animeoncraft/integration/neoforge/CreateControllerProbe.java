package ru.animeoncraft.integration.neoforge;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.contraptions.piston.PistonContraption;
import com.simibubi.create.content.contraptions.pulley.PulleyContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Controller methods on real Create block entities in the guarded disposable world. */
final class CreateControllerProbe {
    private CreateControllerProbe() {}
    record Fixture(BlockEntity controller,ControlledContraptionEntity moving,Contraption contraption) {}
    static void verify(ServerLevel level,Territory own,Territory foreign){
        for(int kind=0;kind<3;kind++){
            for(boolean removal:java.util.List.of(false,true)){
                var root=new BlockPos(own.minX()+40+kind*8,220+(removal?20:0),own.minZ()+40);
                var fixture=create(level,kind,root);
                fixture.moving.setPos(foreign.minX()+24,root.getY(),foreign.minZ()+24);
                var before=fixture.moving.position();
                if(removal)((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)fixture.controller).remove();
                else disassemble(fixture.controller);
                if(current(fixture.controller)!=fixture.moving||fixture.moving.isRemoved()||!fixture.moving.position().equals(before)||fixture.contraption.getBlocks().isEmpty()){
                    throw new IllegalStateException("Create controller lost forbidden mechanism kind="+kind+" removal="+removal);
                }
                var anchor=fixture.contraption.anchor;
                fixture.moving.setPos(anchor.getX(),anchor.getY(),anchor.getZ());
                disassemble(fixture.controller);
                if(current(fixture.controller)!=null||!fixture.moving.isRemoved())throw new IllegalStateException("Create own controller disassembly failed kind="+kind+" removal="+removal);
            }
        }
        var root=new BlockPos(foreign.minX()-2,260,foreign.minZ()+40);
        var fixture=create(level,0,root);
        var piston=(LinearActuatorBlockEntity)fixture.controller;
        piston.offset=1;
        var before=fixture.moving.position();
        disassemble(fixture.controller);
        if(current(fixture.controller)!=fixture.moving||fixture.moving.isRemoved()||!fixture.moving.position().equals(before)){
            throw new IllegalStateException("Piston final position crossed claim: controller_cleared="+(current(fixture.controller)==null)+" moved="+!fixture.moving.position().equals(before));
        }
        piston.offset=0;
        disassemble(fixture.controller);
        if(current(fixture.controller)!=null||!fixture.moving.isRemoved())throw new IllegalStateException("Piston neutral-position positive control failed");
        for(int kind=0;kind<3;kind++)brokenController(level,kind,own,foreign);
        System.out.println("AOC_CREATE_CONTROLLER_PROBE_PASS piston=true pulley=true bearing=true remove_preserves_mechanism=true own_disassembly=true piston_destination_blocked=true broken_controller_tick=true controller_recovery=true own_block_removal=true");
    }
    static Fixture create(ServerLevel level,int kind,BlockPos root){
        String name=switch(kind){case 0->"mechanical_piston";case 1->"rope_pulley";default->"mechanical_bearing";};
        var state=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:"+name)).defaultBlockState();
        if(state.hasProperty(BlockStateProperties.FACING))state=state.setValue(BlockStateProperties.FACING,Direction.EAST);
        level.setBlockAndUpdate(root,state);
        var controller=level.getBlockEntity(root);
        if(!(controller instanceof IControlContraption control))throw new IllegalStateException("Missing Create controller "+name);
        Contraption contraption=switch(kind){case 0->new PistonContraption(Direction.EAST,false);case 1->new PulleyContraption(0);default->new BearingContraption(false,Direction.EAST);};
        contraption.anchor=kind==1?root.below():root.east();
        contraption.bounds=new net.minecraft.world.phys.AABB(0,0,0,1,1,1);
        contraption.getStorage().initialize();
        contraption.getBlocks().put(BlockPos.ZERO,new StructureBlockInfo(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),null));
        var moving=ControlledContraptionEntity.create(level,control,contraption);
        moving.setRotationAxis(Direction.Axis.Y);
        moving.setPos(contraption.anchor.getX(),contraption.anchor.getY(),contraption.anchor.getZ());
        control.attach(moving);
        if(current(controller)!=moving)throw new IllegalStateException("Create fixture failed to attach "+name);
        return new Fixture(controller,moving,contraption);
    }
    private static ControlledContraptionEntity current(BlockEntity controller){
        if(controller instanceof LinearActuatorBlockEntity linear)return (ControlledContraptionEntity)linear.movedContraption;
        return ((MechanicalBearingBlockEntity)controller).getMovedContraption();
    }
    private static void disassemble(BlockEntity controller){
        if(controller instanceof LinearActuatorBlockEntity linear)linear.disassemble();
        else ((MechanicalBearingBlockEntity)controller).disassemble();
    }
    private static void brokenController(ServerLevel level,int kind,Territory own,Territory foreign){
        var root=new BlockPos(own.minX()+40+kind*8,280,own.minZ()+40);
        var fixture=create(level,kind,root);
        var state=level.getBlockState(root);
        fixture.moving.setPos(foreign.minX()+32,root.getY(),foreign.minZ()+32);
        if(!level.addFreshEntity(fixture.moving))throw new IllegalStateException("Could not register moving entity");
        if(!level.destroyBlock(root,false)||!level.getBlockState(root).isAir())throw new IllegalStateException("Controller block removal did not execute");
        // Execute the real entity tick after the block entity has gone, not just remove().
        fixture.moving.tick();
        if(fixture.moving.isRemoved()||fixture.contraption.getBlocks().isEmpty())throw new IllegalStateException("Broken controller discarded protected mechanism on tick kind="+kind);
        level.setBlockAndUpdate(root,state);
        fixture.moving.tick();
        var replacement=level.getBlockEntity(root);
        if(!(replacement instanceof IControlContraption control)||!control.isAttachedTo(fixture.moving))throw new IllegalStateException("Replacement controller did not recover mechanism kind="+kind);
        var anchor=fixture.contraption.anchor;
        fixture.moving.setPos(anchor.getX(),anchor.getY(),anchor.getZ());
        disassemble(replacement);
        if(!fixture.moving.isRemoved())throw new IllegalStateException("Recovered mechanism did not disassemble kind="+kind);
        var ownRoot=root.above(20);
        var allowed=create(level,kind,ownRoot);
        if(!level.addFreshEntity(allowed.moving))throw new IllegalStateException("Could not register own mechanism");
        if(!level.destroyBlock(ownRoot,false)||!allowed.moving.isRemoved())throw new IllegalStateException("Own block removal failed to restore mechanism kind="+kind);
    }
}

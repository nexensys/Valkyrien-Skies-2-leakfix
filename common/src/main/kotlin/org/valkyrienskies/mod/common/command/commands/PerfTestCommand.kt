package org.valkyrienskies.mod.common.command.commands

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.ClipContext.Block
import net.minecraft.world.level.ClipContext.Fluid
import net.minecraft.world.level.block.Blocks
import org.valkyrienskies.mod.common.assembly.ShipAssembler

object PerfTestCommand {

    fun register(vs: LiteralArgumentBuilder<CommandSourceStack>) {
        vs.then(
            literal("perf-test").then(
                literal("spawn-ship-cube").then(
                    argument("size", IntegerArgumentType.integer(1, 20)).executes { ctx ->
                        val source = ctx.source
                        val player = source.playerOrException
                        val level = source.level as ServerLevel
                        val size = IntegerArgumentType.getInteger(ctx, "size")

                        // Compute spawn center: (size + 10) blocks in the player's look direction
                        val lookVec = player.lookAngle
                        val distance = (size + 10).toDouble()
                        val centerX = player.x + lookVec.x * distance
                        val centerY = player.y + lookVec.y * distance
                        val centerZ = player.z + lookVec.z * distance
                        val totalShips = size * size * size

                        source.sendSuccess({
                            Component.literal("[VS2] Spawning ${size}x${size}x${size} ship cube ($totalShips ships)...")
                        }, true)

                        val overallStart = System.currentTimeMillis()
                        val spacing = 2 // 1 block gap between each 1-block ship

                        // Phase 1: Place all iron blocks
                        val placeStart = System.currentTimeMillis()
                        val blockSets = mutableListOf<Set<BlockPos>>()
                        for (x in 0 until size) {
                            for (y in 0 until size) {
                                for (z in 0 until size) {
                                    val blockX = centerX.toInt() + (x - size / 2) * spacing
                                    val blockY = centerY.toInt() + (y - size / 2) * spacing
                                    val blockZ = centerZ.toInt() + (z - size / 2) * spacing
                                    val pos = BlockPos(blockX, blockY, blockZ)
                                    level.setBlock(pos, Blocks.IRON_BLOCK.defaultBlockState(), 3)
                                    blockSets.add(hashSetOf(pos))
                                }
                            }
                        }
                        val placeMs = System.currentTimeMillis() - placeStart

                        // Phase 2: Batch-assemble all ships at once
                        // This is MUCH faster than sequential assembleToShip because:
                        // - One PacketStopChunkUpdates/Restart for ALL ships
                        // - One executeIf callback instead of N
                        // - Batched connectivity updates
                        val assembleStart = System.currentTimeMillis()
                        val results = try {
                            ShipAssembler.batchAssembleToShips(level, blockSets, 1.0)
                        } catch (e: Exception) {
                            source.sendFailure(Component.literal("[VS2] Batch assembly failed: ${e.message}"))
                            return@executes 0
                        }
                        val assembleMs = System.currentTimeMillis() - assembleStart

                        val totalMs = System.currentTimeMillis() - overallStart

                        source.sendSuccess({
                            Component.literal("[VS2] Created ${results.size} ships in ${totalMs}ms " +
                                "(place: ${placeMs}ms, " +
                                "assemble: ${assembleMs}ms, " +
                                "${totalMs / results.size}ms/ship)")
                        }, true)

                        results.size
                    }
                )
            ).then(literal("jenga").then(
                argument("size", IntegerArgumentType.integer(1, 300)).executes { ctx ->
                    val source = ctx.source
                    val player = source.playerOrException
                    val level = source.level as ServerLevel
                    val size = IntegerArgumentType.getInteger(ctx, "size")

                    // raycast for target pos
                    val clipContext = ClipContext(
                        player.eyePosition,
                        player.eyePosition.add(player.lookAngle.multiply(10.0, 10.0, 10.0)),
                        Block.COLLIDER,
                        Fluid.ANY,
                        null
                    )
                    var target = level.clip(clipContext).blockPos

                    // Hacky fix so that we can raycast the floor without spawning in the floor
                    if (!level.getBlockState(target).isAir) {
                        target = target.above()
                    }

                    val totalShips = size * 3

                    source.sendSuccess({
                        Component.literal("[VS2] Spawning a $size tall jenga ($totalShips ships)...")
                    }, true)

                    val overallStart = System.currentTimeMillis()
                    val placeStart = System.currentTimeMillis()
                    val blockSets = mutableListOf<Set<BlockPos>>()

                    for (y in 0 until size) {
                        val isEvenLayer = (y % 2 == 0)

                        if (isEvenLayer) {
                            // Groups along X (each group spans Z)
                            for (x in 0 until 3) {
                                val tempShipSet = mutableSetOf<BlockPos>()
                                for (z in 0 until 3) {
                                    // Offset our center target by our current iteration coordinates
                                    val pos = BlockPos.containing(target.center.add(x.toDouble(), y.toDouble(), z.toDouble()))

                                    level.setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), 3)
                                    tempShipSet.add(pos)
                                }

                                blockSets.add(tempShipSet)
                            }
                        } else {
                            // Groups along Z (each group spans X)
                            for (z in 0 until 3) {
                                val tempShipSet = mutableSetOf<BlockPos>()

                                for (x in 0 until 3) {
                                    // Offset our center target by our current iteration coordinates
                                    val pos = BlockPos.containing(target.center.add(x.toDouble(), y.toDouble(), z.toDouble()))

                                    level.setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), 3)
                                    tempShipSet.add(pos)
                                }

                                blockSets.add(tempShipSet)
                            }
                        }
                    }

                    val placeMs = System.currentTimeMillis() - placeStart

                    // Phase 2: Batch-assemble all ships at once
                    // This is MUCH faster than sequential assembleToShip because:
                    // - One PacketStopChunkUpdates/Restart for ALL ships
                    // - One executeIf callback instead of N
                    // - Batched connectivity updates
                    val assembleStart = System.currentTimeMillis()
                    val results = try {
                        ShipAssembler.batchAssembleToShips(level, blockSets, 1.0)
                    } catch (e: Exception) {
                        source.sendFailure(Component.literal("[VS2] Batch assembly failed: ${e.message}"))
                        return@executes 0
                    }
                    val assembleMs = System.currentTimeMillis() - assembleStart

                    val totalMs = System.currentTimeMillis() - overallStart

                    source.sendSuccess({
                        Component.literal("[VS2] Created ${results.size} ships in ${totalMs}ms " +
                            "(place: ${placeMs}ms, " +
                            "assemble: ${assembleMs}ms, " +
                            "${totalMs / results.size}ms/ship)")
                    }, true)

                    results.size
                }
            ))
        )
    }
}

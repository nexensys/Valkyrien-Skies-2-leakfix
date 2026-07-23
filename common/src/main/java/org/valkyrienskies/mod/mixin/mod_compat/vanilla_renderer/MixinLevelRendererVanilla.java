package org.valkyrienskies.mod.mixin.mod_compat.vanilla_renderer;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.ListIterator;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelRenderer.RenderChunkInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.core.util.datastructures.BlockPos2ByteOpenHashMap;
import org.valkyrienskies.mod.common.assembly.SeamlessChunksManager;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.ShipRenderer;
import org.valkyrienskies.mod.common.config.ShipRendererKt;
import org.valkyrienskies.mod.common.hooks.VSGameEvents;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import org.valkyrienskies.mod.compat.VSRenderer;
import org.valkyrienskies.mod.mixin.ValkyrienCommonMixinConfigPlugin;
import org.valkyrienskies.mod.mixinducks.client.render.IVSViewAreaMethods;
import org.valkyrienskies.mod.mixin.mod_compat.optifine.RenderChunkInfoAccessorOptifine;
import org.valkyrienskies.mod.mixinducks.mod_compat.vanilla_renderer.LevelRendererDuck;
import org.valkyrienskies.mod.mixinducks.client.render.LevelRendererVanillaDuck;

@Mixin(value = LevelRenderer.class, priority = 999)
public abstract class MixinLevelRendererVanilla implements LevelRendererDuck, LevelRendererVanillaDuck {
    @Unique
    private final WeakHashMap<ClientShip, ObjectList<RenderChunkInfo>> shipRenderChunks = new WeakHashMap<>();
    @Shadow
    private ClientLevel level;

    @Shadow
    @Final
    @Mutable
    private ObjectArrayList<RenderChunkInfo> renderChunksInFrustum;

    @Shadow
    private @Nullable ViewArea viewArea;
    @Shadow
    @Final
    private Minecraft minecraft;
    @Shadow
    @Final
    private AtomicBoolean needsFrustumUpdate;

    @Unique
    private BlockPos2ByteOpenHashMap vs$visibileShipChunks = new BlockPos2ByteOpenHashMap();
    @Unique
    private Long lastMountedShipId = null;
    @Unique
    private ShipTransform lastTransform = null;
    @Unique
    private boolean vs$shipChunkVisibilityDirty = true;
    @Unique
    private long vs$lastShipVisibilitySignature = Long.MIN_VALUE;
    @Unique
    private int vs$lastShipVisibilityCount = -1;
    @Unique
    private boolean vs$emittedShipsStartRenderingThisFrame = false;
    @Unique
    private boolean vs$didApplyFrustumThisFrame = false;
    @Unique
    private int vs$lastShipFrustumTailCount = 0;

    @Unique
    private static long vs$quantizeRenderCoord(final double value) {
        return Math.round(value * 256.0);
    }

    @Unique
    private static long vs$shipVisibilitySignature(final ClientShip ship) {
        long sig = ship.getId();
        final AABBdc renderAabb = ship.getRenderAABB();
        sig = 31L * sig + vs$quantizeRenderCoord(renderAabb.minX());
        sig = 31L * sig + vs$quantizeRenderCoord(renderAabb.minY());
        sig = 31L * sig + vs$quantizeRenderCoord(renderAabb.minZ());
        sig = 31L * sig + vs$quantizeRenderCoord(renderAabb.maxX());
        sig = 31L * sig + vs$quantizeRenderCoord(renderAabb.maxY());
        sig = 31L * sig + vs$quantizeRenderCoord(renderAabb.maxZ());
        return sig;
    }

    /**
     * Fix the distance to render chunks, so that MC doesn't think ship chunks are too far away
     */
    @Redirect(
        method = "compileChunks",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;distSqr(Lnet/minecraft/core/Vec3i;)D"
        ),
        require = 0
    )
    private double includeShipChunksInNearChunks(final BlockPos b, final Vec3i v) {
        return VSGameUtilsKt.squaredDistanceBetweenInclShips(
            level, b.getX(), b.getY(), b.getZ(), v.getX(), v.getY(), v.getZ()
        );
    }

    /**
     * Force frustum update if the ship moves and the camera doesn't
     */
    @ModifyExpressionValue(
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/atomic/AtomicBoolean;compareAndSet(ZZ)Z"
        ),
        method = "setupRender"
    )
    private boolean getNeedsFrustumUpdate(final boolean needsFrustumUpdate) {
        // force frustum update if default behaviour says to OR if the player is mounted to a ship
        final Player player = this.minecraft.player;
        if (player == null || !(VSGameUtilsKt.getShipMountedTo(player) instanceof final ClientShip ship)) {
            this.lastMountedShipId = null;
            this.vs$shipChunkVisibilityDirty = needsFrustumUpdate || this.vs$shipChunkVisibilityDirty;
            return needsFrustumUpdate;
        }
        final ShipTransform transform = ship.getRenderTransform();
        if (this.lastMountedShipId == null || this.lastMountedShipId.longValue() != ship.getId() || this.lastTransform == null) {
            this.lastMountedShipId = ship.getId();
            this.lastTransform = transform;
            this.vs$shipChunkVisibilityDirty = true;
            return true;
        }
        final boolean needUpdate = this.lastTransform != transform && !this.lastTransform.getShipToWorld().equals(transform.getShipToWorld());
        this.lastTransform = transform;
        this.vs$shipChunkVisibilityDirty = needUpdate || needsFrustumUpdate || this.vs$shipChunkVisibilityDirty;
        return needUpdate;
    }

    @Override
    public void vs$setNeedsFrustumUpdate() {
        this.needsFrustumUpdate.set(true);
        this.vs$shipChunkVisibilityDirty = true;
    }

    @Override
    public void vs$setShipChunkVisibilityDirty() {
        this.vs$shipChunkVisibilityDirty = true;
    }

    /**
     * Add ship render chunks to [renderChunks]
     */
    @Inject(
        method = "applyFrustum",
        at = @At("RETURN")
    )
    private void postApplyFrustum(Frustum frustum, CallbackInfo ci){
        // Gradually pre-allocate render chunk GPU buffers so they're ready when ships load
        ((IVSViewAreaMethods) viewArea).vs$fillRenderChunkPool();
        if (!this.vs$didApplyFrustumThisFrame) {
            this.vs$removeShipFrustumTail();
        }
        // This mixin never gets called for IP dimensions, instead we'll call it manually
        vs$addShipVisibleChunks(frustum);
    }

    @Inject(
        method = "renderLevel",
        at = @At("HEAD")
    )
    private void vs$resetShipRenderFrameState(final PoseStack poseStack, final float partialTick, final long finishNanoTime,
        final boolean renderBlockOutline, final Camera camera, final GameRenderer gameRenderer,
        final LightTexture lightTexture, final Matrix4f projectionMatrix, final CallbackInfo ci) {
        this.vs$emittedShipsStartRenderingThisFrame = false;
    }

    @Inject(
        method = "setupRender",
        at = @At("HEAD")
    )
    private void vs$beginSetupRender(final Camera camera, final Frustum frustum, final boolean bl, final boolean bl2,
        final CallbackInfo ci) {
        this.vs$didApplyFrustumThisFrame = false;
    }

    @Inject(
        method = "applyFrustum",
        at = @At("TAIL")
    )
    private void vs$afterApplyFrustum(final Frustum frustum, final CallbackInfo ci) {
        this.vs$didApplyFrustumThisFrame = true;
        this.vs$lastShipFrustumTailCount = 0;
        this.vs$shipChunkVisibilityDirty = true;
    }

    /**
     * Process deferred ship chunk packets BEFORE vanilla's light updates so that
     * ship chunks are loaded and their light is computed before render chunks compile.
     */
    @Inject(
        method = "setupRender",
        at = @At("HEAD")
    )
    private void drainShipChunksBeforeLightUpdate(final Camera camera, final Frustum frustum, final boolean bl, final boolean bl2, final CallbackInfo ci) {
        final SeamlessChunksManager manager = SeamlessChunksManager.get();
        if (manager != null) {
            manager.drainDeferredBatch();
            // Drain all queued light updates so the light engine has the latest data
            while (!level.isLightUpdateQueueEmpty()) {
                level.pollLightUpdates();
            }
        }
    }

    @Unique
    private void vs$removeShipFrustumTail() {
        if (this.vs$lastShipFrustumTailCount <= 0) {
            return;
        }
        final int targetSize = Math.max(0, this.renderChunksInFrustum.size() - this.vs$lastShipFrustumTailCount);
        while (this.renderChunksInFrustum.size() > targetSize) {
            this.renderChunksInFrustum.remove(this.renderChunksInFrustum.size() - 1);
        }
        this.vs$lastShipFrustumTailCount = 0;
    }

    @Unique
    private int vs$appendShipRenderChunksToFrustumList() {
        final int[] appended = {0};
        shipRenderChunks.forEach((ship, chunks) -> {
            for (int i = 0; i < chunks.size(); i++) {
                renderChunksInFrustum.add(chunks.get(i));
                appended[0]++;
            }
        });
        return appended[0];
    }

    @Override
    public void vs$addShipVisibleChunks(final Frustum frustum) {
        long shipVisibilitySignature = 0L;
        int loadedShipCount = 0;
        for (final ClientShip shipObject : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            loadedShipCount++;
            shipVisibilitySignature = 31L * shipVisibilitySignature + vs$shipVisibilitySignature(shipObject);
        }

        if (!this.vs$shipChunkVisibilityDirty &&
            this.vs$lastShipVisibilityCount == loadedShipCount &&
            this.vs$lastShipVisibilitySignature == shipVisibilitySignature
        ) {
            this.vs$lastShipFrustumTailCount = this.vs$appendShipRenderChunksToFrustumList();
            return;
        }

        shipRenderChunks.forEach((ship, chunks) -> chunks.clear());
        vs$visibileShipChunks = new BlockPos2ByteOpenHashMap();
        this.vs$lastShipVisibilityCount = loadedShipCount;
        this.vs$lastShipVisibilitySignature = shipVisibilitySignature;
        this.vs$shipChunkVisibilityDirty = false;

        final IVSViewAreaMethods shipViewArea = (IVSViewAreaMethods) viewArea;
        final AABBd tempAABB = new AABBd();
        for (final ClientShip shipObject : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (ShipRendererKt.getShipRenderer(shipObject) != ShipRenderer.VANILLA)
                continue;

            // Don't bother rendering the ship if its AABB isn't visible to the frustum
            if (!frustum.isVisible(VectorConversionsMCKt.toMinecraft(shipObject.getRenderAABB())))
                continue;

            final var shipToWorld = shipObject.getRenderTransform().getShipToWorld();

            shipObject.getActiveChunksSet().forEach((x, z) -> {
                final LevelChunk levelChunk = level.getChunk(x, z);
                for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
                    // Don't add ship chunks more than once
                    if (vs$visibileShipChunks.contains(x, y, z)) {
                        continue;
                    }
                    // If the chunk section is empty then skip it early
                    final LevelChunkSection levelChunkSection = levelChunk.getSection(y - level.getMinSection());
                    if (levelChunkSection.hasOnlyAir()) {
                        continue;
                    }

                    // Use direct ship render chunk lookup — bypasses getShipManagingPos
                    ChunkRenderDispatcher.RenderChunk renderChunk = shipViewArea.vs$getShipRenderChunk(x, y, z);
                    if (renderChunk == null) {
                        renderChunk = shipViewArea.vs$getOrCreateShipRenderChunk(x, y, z);
                    }
                    if (renderChunk != null) {

                        // If the chunk isn't in the frustum then skip it (reuse tempAABB)
                        tempAABB.setMin((x << 4) - 6e-1, (y << 4) - 6e-1, (z << 4) - 6e-1);
                        tempAABB.setMax((x << 4) + 15.6, (y << 4) + 15.6, (z << 4) + 15.6);
                        tempAABB.transform(shipToWorld);

                        if (!frustum.isVisible(VectorConversionsMCKt.toMinecraft(tempAABB))) {
                            continue;
                        }

                        final LevelRenderer.RenderChunkInfo newChunkInfo;
                        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() == VSRenderer.OPTIFINE) {
                            newChunkInfo =
                                RenderChunkInfoAccessorOptifine.vs$new(renderChunk, null, 0);
                        } else {
                            newChunkInfo =
                                RenderChunkInfoAccessor.vs$new(renderChunk, null, 0);
                        }
                        shipRenderChunks.computeIfAbsent(shipObject, k -> new ObjectArrayList<>()).add(newChunkInfo);
                        vs$visibileShipChunks.put(x, y, z, (byte) 1);
                    }
                }
            });
        }

        this.vs$lastShipFrustumTailCount = this.vs$appendShipRenderChunksToFrustumList();
    }

    @Override
    public boolean vs$isShipChunkVisible(final int chunkX, final int sectionY, final int chunkZ) {
        return vs$visibileShipChunks.contains(chunkX, sectionY, chunkZ);
    }

    @WrapOperation(
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V"
        ),
        method = "renderLevel"
    )
    private void redirectRenderChunkLayer(final LevelRenderer receiver,
        final RenderType renderType, final PoseStack poseStack, final double camX, final double camY, final double camZ,
        final Matrix4f matrix4f, final Operation<Void> renderChunkLayer) {

        renderChunkLayer.call(receiver, renderType, poseStack, camX, camY, camZ, matrix4f);

        if (!shipRenderChunks.isEmpty()) {
            if (!this.vs$emittedShipsStartRenderingThisFrame) {
                this.vs$emittedShipsStartRenderingThisFrame = true;
                VSGameEvents.INSTANCE.getShipsStartRendering().emit(new VSGameEvents.ShipStartRenderEvent(
                    receiver, renderType, poseStack, camX, camY, camZ, matrix4f
                ));
            }
            renderAllShipChunkLayers(renderType, poseStack, camX, camY, camZ, matrix4f, receiver);
        }
    }

    /**
     * Batched ship rendering: sets up the shader state ONCE per render type, then draws
     * all ships by only updating the model-view matrix between them. Without batching,
     * 100 ships would require 100 full shader setup/teardown cycles per render type
     * (300+ OpenGL state changes per frame). With batching, it's just 1 setup + 100
     * lightweight matrix updates.
     */
    @Unique
    private void renderAllShipChunkLayers(final RenderType renderType, final PoseStack poseStack,
        final double camX, final double camY, final double camZ,
        final Matrix4f matrix4f, final LevelRenderer receiver) {

        RenderSystem.assertOnRenderThread();
        renderType.setupRenderState();
        this.minecraft.getProfiler().push("vs_ship_render");

        final boolean forwardOrder = renderType != RenderType.translucent();
        final ShaderInstance shaderInstance = RenderSystem.getShader();

        // Set up shader state once for all ships
        for (int k = 0; k < 12; ++k) {
            int l = RenderSystem.getShaderTexture(k);
            shaderInstance.setSampler("Sampler" + k, l);
        }

        if (shaderInstance.PROJECTION_MATRIX != null) {
            shaderInstance.PROJECTION_MATRIX.set(matrix4f);
        }
        if (shaderInstance.COLOR_MODULATOR != null) {
            shaderInstance.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shaderInstance.FOG_START != null) {
            shaderInstance.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shaderInstance.FOG_END != null) {
            shaderInstance.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shaderInstance.FOG_COLOR != null) {
            shaderInstance.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shaderInstance.FOG_SHAPE != null) {
            shaderInstance.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        if (shaderInstance.TEXTURE_MATRIX != null) {
            shaderInstance.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }
        if (shaderInstance.GAME_TIME != null) {
            shaderInstance.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }

        RenderSystem.setupShaderLights(shaderInstance);

        final Uniform modelViewUniform = shaderInstance.MODEL_VIEW_MATRIX;
        final Uniform chunkOffsetUniform = shaderInstance.CHUNK_OFFSET;

        shipRenderChunks.forEach((ship, chunks) -> {
            poseStack.pushPose();
            final ShipTransform shipTransform = ship.getRenderTransform();
            final Vector3dc cameraShipSpace = shipTransform.getWorldToShip().transformPosition(new Vector3d(camX, camY, camZ));
            VSClientGameUtils.transformRenderWithShip(ship.getRenderTransform(), poseStack,
                cameraShipSpace.x(), cameraShipSpace.y(), cameraShipSpace.z(),
                camX, camY, camZ);

            final var event = new VSGameEvents.ShipRenderEvent(
                receiver, renderType, poseStack, camX, camY, camZ, matrix4f, ship, chunks
            );

            VSGameEvents.INSTANCE.getRenderShip().emit(event);

            // Update only the model-view matrix for this ship (the only thing that changes)
            if (modelViewUniform != null) {
                modelViewUniform.set(poseStack.last().pose());
            }
            shaderInstance.apply();

            // Draw all chunks for this ship
            final ListIterator<RenderChunkInfo> it = chunks.listIterator(forwardOrder ? 0 : chunks.size());
            while (forwardOrder ? it.hasNext() : it.hasPrevious()) {
                final RenderChunkInfo info = forwardOrder ? it.next() : it.previous();
                final ChunkRenderDispatcher.RenderChunk renderChunk = info.chunk;
                if (!renderChunk.getCompiledChunk().isEmpty(renderType)) {
                    final VertexBuffer vertexBuffer = renderChunk.getBuffer(renderType);
                    final BlockPos blockPos = renderChunk.getOrigin();
                    if (chunkOffsetUniform != null) {
                        chunkOffsetUniform.set(
                            (float) ((double) blockPos.getX() - cameraShipSpace.x()),
                            (float) ((double) blockPos.getY() - cameraShipSpace.y()),
                            (float) ((double) blockPos.getZ() - cameraShipSpace.z()));
                        chunkOffsetUniform.upload();
                    }
                    vertexBuffer.bind();
                    vertexBuffer.draw();
                }
            }

            VSGameEvents.INSTANCE.getPostRenderShip().emit(event);
            poseStack.popPose();
        });

        if (chunkOffsetUniform != null) {
            chunkOffsetUniform.set(new Vector3f());
        }

        shaderInstance.clear();
        VertexBuffer.unbind();
        this.minecraft.getProfiler().pop();
        renderType.clearRenderState();
    }

    @Override
    public VisibleChunkData vs$captureShipVisibleChunks() {
        WeakHashMap<ClientShip, ObjectList<RenderChunkInfo>> temp = new WeakHashMap<>();
        shipRenderChunks.forEach((ship, chunks) -> {
            ObjectArrayList<RenderChunkInfo> subTemp = new ObjectArrayList<>();
            chunks.forEach(subTemp::add);
            temp.put(ship, subTemp);
        });
        return new VisibleChunkData(temp, vs$visibileShipChunks);
    }

    @Override
    public void vs$reloadShipVisibleChunks(VisibleChunkData data) {
        this.vs$visibileShipChunks = data.visibleShipChunks();
        shipRenderChunks.forEach((ship, chunks) -> chunks.clear());
        shipRenderChunks.clear();
        this.shipRenderChunks.putAll(data.shipRenderChunks());
    }
}

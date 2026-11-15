package net.vulkanmod.vulkan.queue;

import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Graphics queue with an optional upload batch. While a batch records, uploads and layout changes
 * from the recording thread share one command buffer that is submitted later on this same queue,
 * ahead of the commands that read its results. Submission order plus the barriers recorded with
 * each upload then provide the dependency, so nothing waits on a CPU fence. The buffer is recycled
 * through the frame ops of the frame slot that submitted it. Other threads keep the immediate path.
 * <p>
 * Batches nest: the frame batch of {@link net.vulkanmod.vulkan.Renderer} may enclose the texture
 * tick's own batch, and only the outermost end submits.
 */
public class GraphicsQueue extends Queue {
    public static GraphicsQueue INSTANCE;

    private static CommandPool.CommandBuffer currentCmdBuffer;
    private static volatile Thread recordingThread;
    private static int recordingDepth;
    /** Buffer copies recorded into the open batch still need their one visibility barrier. */
    private static boolean pendingBufferBarrier;

    public GraphicsQueue(MemoryStack stack, int familyIndex) {
        super(stack, familyIndex);
    }

    public void startRecording() {
        if (recordingDepth > 0 && recordingThread != Thread.currentThread())
            throw new IllegalStateException("Graphics upload batch is owned by " + recordingThread.getName());
        if (recordingDepth++ == 0) {
            currentCmdBuffer = beginCommands();
            recordingThread = Thread.currentThread();
        }
    }

    public void endRecordingAndSubmit() {
        if (recordingDepth == 0 || recordingThread != Thread.currentThread())
            throw new IllegalStateException("No graphics upload batch to end on this thread");
        if (--recordingDepth > 0) return;
        submitBatch();
        currentCmdBuffer = null;
        recordingThread = null;
    }

    /**
     * Closes the outermost batch level without submitting it. The caller ends the returned command
     * buffer and submits it ahead of its own in the same vkQueueSubmit, or hands it back to
     * {@link #submitDetached}. Returns null while an outer level is still open.
     */
    public CommandPool.CommandBuffer detachBatch() {
        if (recordingDepth == 0 || recordingThread != Thread.currentThread())
            throw new IllegalStateException("No graphics upload batch to end on this thread");
        if (--recordingDepth > 0) return null;
        closeBufferUploads();
        CommandPool.CommandBuffer batch = currentCmdBuffer;
        currentCmdBuffer = null;
        recordingThread = null;
        return batch;
    }

    /** Whether {@link #detachBatch} on this thread would hand out the batch (no nested level is open). */
    public boolean atOutermostBatch() {
        return recordingDepth == 1 && recordingThread == Thread.currentThread();
    }

    /** Submits a batch returned by {@link #detachBatch} on its own. */
    public void submitDetached(CommandPool.CommandBuffer batch) {
        submitCommands(batch);
        MemoryManager.getInstance().addFrameOp(batch::reset);
    }

    /** Submits what the open batch holds so far, before a mid-frame submission, and keeps batching. */
    public void flushBatch() {
        if (currentCmdBuffer == null || recordingThread != Thread.currentThread()) return;
        submitBatch();
        currentCmdBuffer = beginCommands();
    }

    /** Any other submission on this queue first submits earlier batched uploads, keeping their order. */
    @Override
    public synchronized long submitCommands(CommandPool.CommandBuffer commandBuffer) {
        if (commandBuffer != currentCmdBuffer && isBatching()) flushBatch();
        return super.submitCommands(commandBuffer);
    }

    public boolean isBatching() {
        return currentCmdBuffer != null && recordingThread == Thread.currentThread();
    }

    /**
     * Records a staged copy into a device-local buffer into the open batch (recording thread only).
     * Before, each such copy (DH LODs, VBOs) was its own transfer-queue submission with a fence the
     * next frame submission waited on, and the 50th of a frame waited mid-frame (flight stutter).
     * One barrier at the end of the batch makes all copies visible to the frame's later reads.
     */
    public void recordBufferUpload(long srcBuffer, long srcOffset, long dstBuffer, long dstOffset, long size) {
        TransferQueue.uploadBufferCmd(currentCmdBuffer.getHandle(), srcBuffer, srcOffset, dstBuffer, dstOffset, size);
        pendingBufferBarrier = true;
        net.vulkanmod.render.optimization.ChunkFrameDiagnostics.recordBatchedBufferUpload(size);
    }

    private static void closeBufferUploads() {
        if (!pendingBufferBarrier || currentCmdBuffer == null) return;
        pendingBufferBarrier = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack);
            barrier.sType$Default();
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_SHADER_READ_BIT
                    | VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT
                    | VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(currentCmdBuffer.getHandle(), VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, barrier, null, null);
        }
    }

    private void submitBatch() {
        closeBufferUploads();
        CommandPool.CommandBuffer batch = currentCmdBuffer;
        submitCommands(batch);
        MemoryManager.getInstance().addFrameOp(batch::reset);
    }

    public CommandPool.CommandBuffer getCommandBuffer() {
        if (isBatching()) {
            return currentCmdBuffer;
        } else {
            return beginCommands();
        }
    }

    public long endIfNeeded(CommandPool.CommandBuffer commandBuffer) {
        if (commandBuffer == currentCmdBuffer) {
            return VK_NULL_HANDLE;
        } else {
            return submitCommands(commandBuffer);
        }
    }

}

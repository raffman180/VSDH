package net.vulkanmod.vulkan.present;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.vulkanmod.vulkan.present.Win32Com.*;

/**
 * DXGI flip presentation through D3D12 (-Dvsdh.dxgiPresent, see DeviceManager.dxgiMode). The shared
 * textures and fences are native D3D12 objects (an earlier D3D11 interop was slower, its immediate
 * context sat between Vulkan and the swapchain). The copies into the back buffers are
 * recorded once per (texture, back buffer) pair and only executed per frame.
 * <p>
 * The copy runs on the swapchain's direct queue. A separate copy queue was tried and removed: on an
 * RTX 4070 the first copies into a back buffer from a copy queue removed the device (DXGI_ERROR_ACCESS_DENIED).
 * No CPU waits per frame; resizing and closing wait for the queue. Render thread only.
 */
public final class D3d12Presenter implements DxgiBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("VSDH-Present");
    private static final int SWAPCHAIN_BUFFERS = 3, MAX_BUFFERS = 8;

    // IIDs from the Windows SDK 10.0.28000 headers (dxgi*.h, d3d12.h).
    private static final String IID_IDXGIFactory2 = "50c83a1c-e072-4c48-87b0-3630fa36a6d0";
    private static final String IID_IDXGIFactory4 = "1bc6ea02-ef36-464f-bf0c-21ca39e5168a";
    private static final String IID_IDXGIFactory5 = "7632e1f5-ee65-4dca-87fd-84cd75f8838d";
    private static final String IID_IDXGIAdapter1 = "29038f61-3839-4626-91fd-086879011a05";
    private static final String IID_IDXGISwapChain3 = "94d99bdb-f1f8-4ab0-b236-7da0170edab1";
    private static final String IID_ID3D12Device = "189819f1-1db6-4b57-be54-1821339b85f7";
    private static final String IID_ID3D12CommandQueue = "0ec870a6-5d7e-4c22-8cfc-5baae07616ed";
    private static final String IID_ID3D12CommandAllocator = "6102dee4-af59-4b09-b999-b44d73f09b24";
    private static final String IID_ID3D12GraphicsCommandList = "5b160d0f-ac1b-4185-8ba8-b3ae42a5a455";
    private static final String IID_ID3D12Fence = "0a753dcf-c4d8-4b91-adf6-be5a60d95a76";
    private static final String IID_ID3D12Resource = "696442be-a72e-4059-bc79-5b5c98040fad";

    // Vtable slots (Windows SDK 10.0.28000). d3d12.h declares struct-returning methods twice in its C
    // vtables (#if/#else); only one of them is a slot (GetResourceAllocationInfo 25, GetCustomHeapProperties 26).
    private static final int FACTORY_MAKE_WINDOW_ASSOCIATION = 8, FACTORY2_CREATE_SWAPCHAIN_FOR_HWND = 15,
            FACTORY4_ENUM_ADAPTER_BY_LUID = 26, FACTORY5_CHECK_FEATURE_SUPPORT = 28;
    private static final int SWAPCHAIN_PRESENT = 8, SWAPCHAIN_GET_BUFFER = 9, SWAPCHAIN_RESIZE_BUFFERS = 13,
            SWAPCHAIN3_GET_CURRENT_BACK_BUFFER_INDEX = 36;
    private static final int DEVICE_CREATE_COMMAND_QUEUE = 8, DEVICE_CREATE_COMMAND_ALLOCATOR = 9,
            DEVICE_CREATE_COMMAND_LIST = 12, DEVICE_CREATE_COMMITTED_RESOURCE = 27, DEVICE_CREATE_SHARED_HANDLE = 31,
            DEVICE_CREATE_FENCE = 36, DEVICE_GET_DEVICE_REMOVED_REASON = 37;
    private static final int QUEUE_EXECUTE_COMMAND_LISTS = 10, QUEUE_SIGNAL = 14, QUEUE_WAIT = 15;
    private static final int LIST_CLOSE = 9, LIST_COPY_RESOURCE = 17, LIST_RESOURCE_BARRIER = 26;
    private static final int FENCE_SET_EVENT_ON_COMPLETION = 9, ALLOCATOR_RESET = 8;

    // Constants (Windows SDK 10.0.28000).
    private static final int DXGI_FORMAT_R8G8B8A8_UNORM = 28, DXGI_USAGE_RENDER_TARGET_OUTPUT = 0x20,
            DXGI_SCALING_STRETCH = 0, DXGI_SWAP_EFFECT_FLIP_DISCARD = 4, DXGI_ALPHA_MODE_IGNORE = 3,
            DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING = 0x800, DXGI_PRESENT_ALLOW_TEARING = 0x200,
            DXGI_MWA_NO_WINDOW_CHANGES = 1, DXGI_MWA_NO_ALT_ENTER = 2, DXGI_FEATURE_PRESENT_ALLOW_TEARING = 0;
    private static final int D3D_FEATURE_LEVEL_11_0 = 0xb000;
    private static final int D3D12_COMMAND_LIST_TYPE_DIRECT = 0;
    private static final int D3D12_HEAP_TYPE_DEFAULT = 1, D3D12_HEAP_FLAG_SHARED = 0x1;
    private static final int D3D12_RESOURCE_DIMENSION_TEXTURE2D = 3, D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET = 0x1;
    private static final int D3D12_RESOURCE_STATE_COMMON = 0, D3D12_RESOURCE_STATE_PRESENT = 0,
            D3D12_RESOURCE_STATE_COPY_DEST = 0x400;
    private static final int D3D12_RESOURCE_BARRIER_TYPE_TRANSITION = 0, D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES = 0xffffffff;
    private static final int D3D12_FENCE_FLAG_NONE = 0, D3D12_FENCE_FLAG_SHARED = 0x1;
    private static final long GENERIC_ALL = 0x10000000L;

    private long factory, device, queue, allocator, swapchain;
    private int bufferCount;
    private final long[] backBuffers = new long[MAX_BUFFERS];
    private final long[] textures = new long[IMAGES];
    private final long[] textureHandles = new long[IMAGES];
    private final long[] lists = new long[IMAGES * MAX_BUFFERS];
    private long renderFence, copyFence, idleFence, renderFenceHandle, copyFenceHandle;
    private long copyValue, idleValue;
    private long listPointer;
    private boolean tearing;
    private int width, height;
    private boolean closed;

    private D3d12Presenter() {}

    /** A presenter for {@code hwnd} on the adapter with Vulkan's device LUID, or null (one log line). */
    public static D3d12Presenter create(long hwnd, long adapterLuid, int width, int height) {
        var presenter = new D3d12Presenter();
        try {
            presenter.init(hwnd, adapterLuid, width, height);
            LOGGER.info("DXGI flip presentation (D3D12) active: {}x{}, {} shared images, {} back buffers, tearing {}",
                    width, height, IMAGES, presenter.bufferCount, presenter.tearing);
            return presenter;
        } catch (RuntimeException failure) {
            LOGGER.warn("DXGI flip presentation (D3D12) unavailable, using the Vulkan swapchain: {}", failure.getMessage());
            presenter.close();
            return null;
        }
    }

    private void init(long hwnd, long adapterLuid, int width, int height) {
        if (hwnd == 0) throw new IllegalStateException("no window handle");
        bufferCount = Math.max(2, Math.min(MAX_BUFFERS, Integer.getInteger("vsdh.dxgiBuffers", SWAPCHAIN_BUFFERS)));
        listPointer = MemoryUtil.nmemAlloc(8);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.mallocPointer(1).address();

            check(callFunction(function("dxgi.dll", "CreateDXGIFactory2"), 0, guid(stack, IID_IDXGIFactory2), clear(out)), "CreateDXGIFactory2");
            factory = MemoryUtil.memGetAddress(out);

            long factory4 = query(factory, IID_IDXGIFactory4);
            if (factory4 == 0) throw new IllegalStateException("IDXGIFactory4 missing");
            long adapter;
            try {
                check(call(factory4, FACTORY4_ENUM_ADAPTER_BY_LUID, adapterLuid, guid(stack, IID_IDXGIAdapter1), clear(out)), "EnumAdapterByLuid");
                adapter = MemoryUtil.memGetAddress(out);
            } finally {
                release(factory4);
            }

            long factory5 = query(factory, IID_IDXGIFactory5);
            if (factory5 != 0) {
                long allow = MemoryUtil.memAddress(stack.ints(0));
                tearing = call(factory5, FACTORY5_CHECK_FEATURE_SUPPORT, DXGI_FEATURE_PRESENT_ALLOW_TEARING, allow, 4) == S_OK
                        && MemoryUtil.memGetInt(allow) != 0;
                release(factory5);
            }

            try {
                check(callFunction(function("d3d12.dll", "D3D12CreateDevice"), adapter, D3D_FEATURE_LEVEL_11_0,
                        guid(stack, IID_ID3D12Device), clear(out)), "D3D12CreateDevice");
            } finally {
                release(adapter);
            }
            device = MemoryUtil.memGetAddress(out);

            queue = createQueue(stack, out, D3D12_COMMAND_LIST_TYPE_DIRECT);
            check(call(device, DEVICE_CREATE_COMMAND_ALLOCATOR, D3D12_COMMAND_LIST_TYPE_DIRECT, guid(stack, IID_ID3D12CommandAllocator), clear(out)),
                    "CreateCommandAllocator");
            allocator = MemoryUtil.memGetAddress(out);

            renderFence = createFence(stack, out, D3D12_FENCE_FLAG_SHARED);
            renderFenceHandle = sharedHandle(stack, renderFence, out);
            copyFence = createFence(stack, out, D3D12_FENCE_FLAG_SHARED);
            copyFenceHandle = sharedHandle(stack, copyFence, out);
            idleFence = createFence(stack, out, D3D12_FENCE_FLAG_NONE);

            long desc = MemoryUtil.memAddress(stack.calloc(4, 48)); // DXGI_SWAP_CHAIN_DESC1
            MemoryUtil.memPutInt(desc, width);
            MemoryUtil.memPutInt(desc + 4, height);
            MemoryUtil.memPutInt(desc + 8, DXGI_FORMAT_R8G8B8A8_UNORM);
            MemoryUtil.memPutInt(desc + 12, 0);                       // Stereo
            MemoryUtil.memPutInt(desc + 16, 1);                       // SampleDesc.Count
            MemoryUtil.memPutInt(desc + 20, 0);                       // SampleDesc.Quality
            MemoryUtil.memPutInt(desc + 24, DXGI_USAGE_RENDER_TARGET_OUTPUT);
            MemoryUtil.memPutInt(desc + 28, bufferCount);
            MemoryUtil.memPutInt(desc + 32, DXGI_SCALING_STRETCH);
            MemoryUtil.memPutInt(desc + 36, DXGI_SWAP_EFFECT_FLIP_DISCARD);
            MemoryUtil.memPutInt(desc + 40, DXGI_ALPHA_MODE_IGNORE);
            MemoryUtil.memPutInt(desc + 44, swapchainFlags());
            // For D3D12 the "device" argument is the direct queue that presents.
            check(call(factory, FACTORY2_CREATE_SWAPCHAIN_FOR_HWND, queue, hwnd, desc, 0, 0, clear(out)), "CreateSwapChainForHwnd");
            long swapchain1 = MemoryUtil.memGetAddress(out);
            swapchain = query(swapchain1, IID_IDXGISwapChain3);
            release(swapchain1);
            if (swapchain == 0) throw new IllegalStateException("IDXGISwapChain3 missing");
            call(factory, FACTORY_MAKE_WINDOW_ASSOCIATION, hwnd, DXGI_MWA_NO_ALT_ENTER | DXGI_MWA_NO_WINDOW_CHANGES);

            createTargets(width, height);
        }
    }

    private int swapchainFlags() {
        return tearing ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0;
    }

    private long createQueue(MemoryStack stack, long out, int type) {
        long desc = MemoryUtil.memAddress(stack.calloc(4, 16)); // D3D12_COMMAND_QUEUE_DESC: Type, Priority, Flags, NodeMask
        MemoryUtil.memPutInt(desc, type);
        check(call(device, DEVICE_CREATE_COMMAND_QUEUE, desc, guid(stack, IID_ID3D12CommandQueue), clear(out)), "CreateCommandQueue");
        return MemoryUtil.memGetAddress(out);
    }

    private long createFence(MemoryStack stack, long out, int flags) {
        check(call(device, DEVICE_CREATE_FENCE, 0, flags, guid(stack, IID_ID3D12Fence), clear(out)), "CreateFence");
        return MemoryUtil.memGetAddress(out);
    }

    private long sharedHandle(MemoryStack stack, long object, long out) {
        check(call(device, DEVICE_CREATE_SHARED_HANDLE, object, 0, GENERIC_ALL, 0, clear(out)), "ID3D12Device::CreateSharedHandle");
        return MemoryUtil.memGetAddress(out);
    }

    /** Back buffer references, shared textures and the prerecorded copies for the current size. */
    private void createTargets(int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.mallocPointer(1).address();
            for (int b = 0; b < bufferCount; b++) {
                check(call(swapchain, SWAPCHAIN_GET_BUFFER, b, guid(stack, IID_ID3D12Resource), clear(out)), "GetBuffer");
                backBuffers[b] = MemoryUtil.memGetAddress(out);
            }

            long heap = MemoryUtil.memAddress(stack.calloc(4, 20)); // D3D12_HEAP_PROPERTIES
            MemoryUtil.memPutInt(heap, D3D12_HEAP_TYPE_DEFAULT);
            long desc = MemoryUtil.memAddress(stack.calloc(8, 56)); // D3D12_RESOURCE_DESC
            MemoryUtil.memPutInt(desc, D3D12_RESOURCE_DIMENSION_TEXTURE2D);
            MemoryUtil.memPutLong(desc + 8, 0);                       // Alignment
            MemoryUtil.memPutLong(desc + 16, width);                  // Width
            MemoryUtil.memPutInt(desc + 24, height);                  // Height
            MemoryUtil.memPutShort(desc + 28, (short) 1);             // DepthOrArraySize
            MemoryUtil.memPutShort(desc + 30, (short) 1);             // MipLevels
            MemoryUtil.memPutInt(desc + 32, DXGI_FORMAT_R8G8B8A8_UNORM);
            MemoryUtil.memPutInt(desc + 36, 1);                       // SampleDesc.Count
            MemoryUtil.memPutInt(desc + 40, 0);                       // SampleDesc.Quality
            MemoryUtil.memPutInt(desc + 44, 0);                       // Layout UNKNOWN
            MemoryUtil.memPutInt(desc + 48, D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET);
            for (int i = 0; i < IMAGES; i++) {
                check(call(device, DEVICE_CREATE_COMMITTED_RESOURCE, heap, D3D12_HEAP_FLAG_SHARED, desc, D3D12_RESOURCE_STATE_COMMON,
                        0, guid(stack, IID_ID3D12Resource), clear(out)), "CreateCommittedResource");
                textures[i] = MemoryUtil.memGetAddress(out);
                textureHandles[i] = sharedHandle(stack, textures[i], out);
            }

            long barrier = MemoryUtil.memAddress(stack.calloc(8, 32)); // D3D12_RESOURCE_BARRIER (transition)
            for (int i = 0; i < IMAGES; i++) {
                for (int b = 0; b < bufferCount; b++) {
                    check(call(device, DEVICE_CREATE_COMMAND_LIST, 0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocator, 0,
                            guid(stack, IID_ID3D12GraphicsCommandList), clear(out)), "CreateCommandList");
                    long list = MemoryUtil.memGetAddress(out);
                    lists[i * MAX_BUFFERS + b] = list;
                    // The shared texture is promoted from COMMON to COPY_SOURCE and decays on its own; the
                    // written back buffer has to return to PRESENT explicitly.
                    transition(list, barrier, backBuffers[b], D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_COPY_DEST);
                    call(list, LIST_COPY_RESOURCE, backBuffers[b], textures[i]);
                    transition(list, barrier, backBuffers[b], D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_PRESENT);
                    check(call(list, LIST_CLOSE), "ID3D12GraphicsCommandList::Close");
                }
            }
        }
        this.width = width;
        this.height = height;
    }

    private static void transition(long list, long barrier, long resource, int before, int after) {
        MemoryUtil.memPutInt(barrier, D3D12_RESOURCE_BARRIER_TYPE_TRANSITION);
        MemoryUtil.memPutInt(barrier + 4, 0);
        MemoryUtil.memPutAddress(barrier + 8, resource);
        MemoryUtil.memPutInt(barrier + 16, D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES);
        MemoryUtil.memPutInt(barrier + 20, before);
        MemoryUtil.memPutInt(barrier + 24, after);
        call(list, LIST_RESOURCE_BARRIER, 1, barrier);
    }

    private void releaseTargets() {
        for (int i = 0; i < lists.length; i++) {
            release(lists[i]);
            lists[i] = 0;
        }
        for (int i = 0; i < IMAGES; i++) {
            closeHandle(textureHandles[i]);
            textureHandles[i] = 0;
            release(textures[i]);
            textures[i] = 0;
        }
        for (int b = 0; b < MAX_BUFFERS; b++) {
            release(backBuffers[b]);
            backBuffers[b] = 0;
        }
    }

    /** Blocks until the queue has finished everything queued so far (resize and close only). */
    private void waitIdle() {
        if (idleFence == 0 || queue == 0) return;
        long value = ++idleValue;
        if (failed(call(queue, QUEUE_SIGNAL, idleFence, value))) return;
        // A null event makes SetEventOnCompletion block until the fence reaches the value.
        call(idleFence, FENCE_SET_EVENT_ON_COMPLETION, value, 0);
    }

    @Override public int memoryHandleType() { return VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT; }
    @Override public long textureHandle(int index) { return textureHandles[index]; }
    @Override public long renderFenceHandle() { return renderFenceHandle; }
    @Override public long copyFenceHandle() { return copyFenceHandle; }
    @Override public int width() { return width; }
    @Override public int height() { return height; }

    @Override
    public long present(int index, long renderValue, boolean vsync) {
        if (closed) return -1;
        call(queue, QUEUE_WAIT, renderFence, renderValue);
        int buffer = call(swapchain, SWAPCHAIN3_GET_CURRENT_BACK_BUFFER_INDEX);
        if (buffer < 0 || buffer >= bufferCount) {
            LOGGER.warn("DXGI back buffer index out of range: {}", buffer);
            return -1;
        }
        MemoryUtil.memPutAddress(listPointer, lists[index * MAX_BUFFERS + buffer]);
        call(queue, QUEUE_EXECUTE_COMMAND_LISTS, 1, listPointer);
        long value = ++copyValue;
        call(queue, QUEUE_SIGNAL, copyFence, value);
        int hr = call(swapchain, SWAPCHAIN_PRESENT, vsync ? 1 : 0, !vsync && tearing ? DXGI_PRESENT_ALLOW_TEARING : 0);
        if (failed(hr)) {
            LOGGER.warn("DXGI Present (D3D12) failed: {}, device removed reason {}", hex(hr), hex(call(device, DEVICE_GET_DEVICE_REMOVED_REASON)));
            return -1;
        }
        return value;
    }

    @Override
    public void resize(int width, int height) {
        waitIdle();
        releaseTargets();
        call(allocator, ALLOCATOR_RESET); // every list of it is released and idle
        check(call(swapchain, SWAPCHAIN_RESIZE_BUFFERS, 0, width, height, 0, swapchainFlags()), "ResizeBuffers");
        createTargets(width, height);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        waitIdle();
        releaseTargets();
        release(swapchain);
        closeHandle(renderFenceHandle);
        closeHandle(copyFenceHandle);
        release(renderFence);
        release(copyFence);
        release(idleFence);
        release(allocator);
        release(queue);
        release(device);
        release(factory);
        swapchain = renderFence = copyFence = idleFence = allocator = queue = device = factory = 0;
        if (listPointer != 0) MemoryUtil.nmemFree(listPointer);
        listPointer = 0;
    }

    private static long clear(long pointer) {
        MemoryUtil.memPutAddress(pointer, 0);
        return pointer;
    }

    private static void check(int hr, String what) {
        if (failed(hr)) throw new IllegalStateException(what + " failed: " + hex(hr));
    }
}

package net.vulkanmod.vulkan.present;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.vulkanmod.vulkan.present.Win32Com.*;

/**
 * Presents Vulkan frames through a DXGI flip-model swapchain, the path GL drivers use for windows on
 * Windows. NVIDIA's native Vulkan present in a window costs ~0.8 ms of GPU time per frame
 * (docs/PERFORMANCE.md); the driver setting "Prefer layered on DXGI swapchain" removes it, and this
 * does the same without asking the player to change anything.
 * <p>
 * D3D11 on the same adapter owns {@link #IMAGES} shared textures that Vulkan imports and renders into,
 * plus two shared fences: Vulkan signals {@code render} when a frame is done, D3D11 waits for it on the
 * GPU, copies the texture into the swapchain's back buffer, signals {@code copy} and presents. Vulkan
 * waits for {@code copy} before it draws into the same texture again. No CPU waits are added.
 * Render thread only.
 */
public final class DxgiPresenter implements DxgiBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("VSDH-Present");
    private static final int SWAPCHAIN_BUFFERS = 3;

    // IIDs from the Windows SDK 10.0.28000 headers.
    private static final String IID_IDXGIFactory2 = "50c83a1c-e072-4c48-87b0-3630fa36a6d0";
    private static final String IID_IDXGIFactory4 = "1bc6ea02-ef36-464f-bf0c-21ca39e5168a";
    private static final String IID_IDXGIFactory5 = "7632e1f5-ee65-4dca-87fd-84cd75f8838d";
    private static final String IID_IDXGIAdapter1 = "29038f61-3839-4626-91fd-086879011a05";
    private static final String IID_IDXGIResource1 = "30961379-4609-4a41-998e-54fe567ee0c1";
    private static final String IID_ID3D11Device5 = "8ffde202-a0e7-45df-9e01-e837801b5ea0";
    private static final String IID_ID3D11DeviceContext4 = "917600da-f58c-4c33-98d8-3e15b390fa24";
    private static final String IID_ID3D11Fence = "affde9d1-1df7-4bb7-8a34-0f46251dab80";
    private static final String IID_ID3D11Texture2D = "6f15aaf2-d208-4e89-9ab4-489535d34f9c";

    // Vtable slots (Windows SDK 10.0.28000).
    private static final int FACTORY_MAKE_WINDOW_ASSOCIATION = 8, FACTORY2_CREATE_SWAPCHAIN_FOR_HWND = 15,
            FACTORY4_ENUM_ADAPTER_BY_LUID = 26, FACTORY5_CHECK_FEATURE_SUPPORT = 28;
    private static final int SWAPCHAIN_PRESENT = 8, SWAPCHAIN_GET_BUFFER = 9, SWAPCHAIN_RESIZE_BUFFERS = 13;
    private static final int RESOURCE1_CREATE_SHARED_HANDLE = 13;
    private static final int DEVICE_CREATE_TEXTURE2D = 5, DEVICE5_CREATE_FENCE = 68;
    private static final int CONTEXT_COPY_RESOURCE = 47, CONTEXT_FLUSH = 111, CONTEXT4_SIGNAL = 147, CONTEXT4_WAIT = 148;
    private static final int FENCE_CREATE_SHARED_HANDLE = 7;

    // Constants (Windows SDK 10.0.28000).
    private static final int DXGI_FORMAT_R8G8B8A8_UNORM = 28, DXGI_USAGE_RENDER_TARGET_OUTPUT = 0x20,
            DXGI_SCALING_STRETCH = 0, DXGI_SWAP_EFFECT_FLIP_DISCARD = 4, DXGI_ALPHA_MODE_IGNORE = 3,
            DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING = 0x800, DXGI_PRESENT_ALLOW_TEARING = 0x200,
            DXGI_MWA_NO_WINDOW_CHANGES = 1, DXGI_MWA_NO_ALT_ENTER = 2, DXGI_FEATURE_PRESENT_ALLOW_TEARING = 0;
    private static final int D3D_DRIVER_TYPE_UNKNOWN = 0, D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20, D3D11_SDK_VERSION = 7;
    private static final int D3D11_USAGE_DEFAULT = 0, D3D11_BIND_SHADER_RESOURCE = 0x8, D3D11_BIND_RENDER_TARGET = 0x20,
            D3D11_RESOURCE_MISC_SHARED = 0x2, D3D11_RESOURCE_MISC_SHARED_NTHANDLE = 0x800, D3D11_FENCE_FLAG_SHARED = 0x2;
    private static final long DXGI_SHARED_RESOURCE_READ_WRITE = 0x80000001L, GENERIC_ALL = 0x10000000L;

    private long factory, device, device5, context, context4, swapchain, backBuffer;
    private final long[] textures = new long[IMAGES];
    private final long[] textureHandles = new long[IMAGES];
    private long renderFence, copyFence, renderFenceHandle, copyFenceHandle;
    private boolean tearing;
    private int width, height;
    private long copyValue;
    private boolean closed;

    private DxgiPresenter() {}

    /**
     * A presenter for {@code hwnd} on the adapter with Vulkan's device LUID, or null (with one log
     * line) when anything on the way is missing: then the normal Vulkan swapchain is used.
     */
    public static DxgiPresenter create(long hwnd, long adapterLuid, int width, int height) {
        var presenter = new DxgiPresenter();
        try {
            presenter.init(hwnd, adapterLuid, width, height);
            LOGGER.info("DXGI flip presentation active: {}x{}, {} shared images, tearing {}", width, height, IMAGES, presenter.tearing);
            return presenter;
        } catch (RuntimeException failure) {
            LOGGER.warn("DXGI flip presentation unavailable, using the Vulkan swapchain: {}", failure.getMessage());
            presenter.close();
            return null;
        }
    }

    private void init(long hwnd, long adapterLuid, int width, int height) {
        if (hwnd == 0) throw new IllegalStateException("no window handle");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.mallocPointer(1).address();

            long createFactory = function("dxgi.dll", "CreateDXGIFactory2");
            check(callFunction(createFactory, 0, guid(stack, IID_IDXGIFactory2), clear(out)), "CreateDXGIFactory2");
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

            long createDevice = function("d3d11.dll", "D3D11CreateDevice");
            long contextOut = stack.mallocPointer(1).address();
            try {
                check(callFunction(createDevice, adapter, D3D_DRIVER_TYPE_UNKNOWN, 0, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                        0, 0, D3D11_SDK_VERSION, clear(out), 0, clear(contextOut)), "D3D11CreateDevice");
            } finally {
                release(adapter);
            }
            device = MemoryUtil.memGetAddress(out);
            context = MemoryUtil.memGetAddress(contextOut);
            device5 = query(device, IID_ID3D11Device5);
            context4 = query(context, IID_ID3D11DeviceContext4);
            if (device5 == 0 || context4 == 0) throw new IllegalStateException("D3D11.4 shared fences unavailable");
            // 8 queued frames measured best (Photon 1440p window: latency 1 143 FPS, default 140, 8 148).
            int latency = Integer.getInteger("vsdh.dxgiLatency", 8);
            if (latency > 0) {
                long dxgiDevice = query(device, "77db970f-6276-48ba-ba28-070143b4392c"); // IDXGIDevice1
                if (dxgiDevice != 0) {
                    call(dxgiDevice, 12, latency); // SetMaximumFrameLatency
                    release(dxgiDevice);
                }
            }

            renderFence = createFence(stack, out);
            renderFenceHandle = sharedFenceHandle(stack, renderFence, out);
            copyFence = createFence(stack, out);
            copyFenceHandle = sharedFenceHandle(stack, copyFence, out);

            long desc = MemoryUtil.memAddress(stack.calloc(4, 48));
            MemoryUtil.memPutInt(desc, width);
            MemoryUtil.memPutInt(desc + 4, height);
            MemoryUtil.memPutInt(desc + 8, DXGI_FORMAT_R8G8B8A8_UNORM);
            MemoryUtil.memPutInt(desc + 12, 0);                       // Stereo
            MemoryUtil.memPutInt(desc + 16, 1);                       // SampleDesc.Count
            MemoryUtil.memPutInt(desc + 20, 0);                       // SampleDesc.Quality
            MemoryUtil.memPutInt(desc + 24, DXGI_USAGE_RENDER_TARGET_OUTPUT);
            MemoryUtil.memPutInt(desc + 28, Integer.getInteger("vsdh.dxgiBuffers", SWAPCHAIN_BUFFERS));
            MemoryUtil.memPutInt(desc + 32, DXGI_SCALING_STRETCH);
            MemoryUtil.memPutInt(desc + 36, DXGI_SWAP_EFFECT_FLIP_DISCARD);
            MemoryUtil.memPutInt(desc + 40, DXGI_ALPHA_MODE_IGNORE);
            MemoryUtil.memPutInt(desc + 44, swapchainFlags());
            check(call(factory, FACTORY2_CREATE_SWAPCHAIN_FOR_HWND, device, hwnd, desc, 0, 0, clear(out)), "CreateSwapChainForHwnd");
            swapchain = MemoryUtil.memGetAddress(out);
            // GLFW keeps the window; DXGI must not react to Alt+Enter or resize it.
            call(factory, FACTORY_MAKE_WINDOW_ASSOCIATION, hwnd, DXGI_MWA_NO_ALT_ENTER | DXGI_MWA_NO_WINDOW_CHANGES);

            createTargets(width, height);
        }
    }

    private int swapchainFlags() {
        return tearing ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0;
    }

    private long createFence(MemoryStack stack, long out) {
        check(call(device5, DEVICE5_CREATE_FENCE, 0, D3D11_FENCE_FLAG_SHARED, guid(stack, IID_ID3D11Fence), clear(out)), "CreateFence");
        return MemoryUtil.memGetAddress(out);
    }

    private static long sharedFenceHandle(MemoryStack stack, long fence, long out) {
        check(call(fence, FENCE_CREATE_SHARED_HANDLE, 0, GENERIC_ALL, 0, clear(out)), "ID3D11Fence::CreateSharedHandle");
        return MemoryUtil.memGetAddress(out);
    }

    /** Back buffer reference and the shared textures for the current size. */
    private void createTargets(int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.mallocPointer(1).address();
            check(call(swapchain, SWAPCHAIN_GET_BUFFER, 0, guid(stack, IID_ID3D11Texture2D), clear(out)), "GetBuffer");
            backBuffer = MemoryUtil.memGetAddress(out);

            long desc = MemoryUtil.memAddress(stack.calloc(4, 44));
            MemoryUtil.memPutInt(desc, width);
            MemoryUtil.memPutInt(desc + 4, height);
            MemoryUtil.memPutInt(desc + 8, 1);                        // MipLevels
            MemoryUtil.memPutInt(desc + 12, 1);                       // ArraySize
            MemoryUtil.memPutInt(desc + 16, DXGI_FORMAT_R8G8B8A8_UNORM);
            MemoryUtil.memPutInt(desc + 20, 1);                       // SampleDesc.Count
            MemoryUtil.memPutInt(desc + 24, 0);
            MemoryUtil.memPutInt(desc + 28, D3D11_USAGE_DEFAULT);
            MemoryUtil.memPutInt(desc + 32, D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE);
            MemoryUtil.memPutInt(desc + 36, 0);
            MemoryUtil.memPutInt(desc + 40, D3D11_RESOURCE_MISC_SHARED | D3D11_RESOURCE_MISC_SHARED_NTHANDLE);
            for (int i = 0; i < IMAGES; i++) {
                check(call(device, DEVICE_CREATE_TEXTURE2D, desc, 0, clear(out)), "CreateTexture2D");
                textures[i] = MemoryUtil.memGetAddress(out);
                long resource = query(textures[i], IID_IDXGIResource1);
                if (resource == 0) throw new IllegalStateException("IDXGIResource1 missing");
                try {
                    check(call(resource, RESOURCE1_CREATE_SHARED_HANDLE, 0, DXGI_SHARED_RESOURCE_READ_WRITE, 0, clear(out)), "CreateSharedHandle");
                    textureHandles[i] = MemoryUtil.memGetAddress(out);
                } finally {
                    release(resource);
                }
            }
        }
        this.width = width;
        this.height = height;
    }

    private void releaseTargets() {
        for (int i = 0; i < IMAGES; i++) {
            closeHandle(textureHandles[i]);
            textureHandles[i] = 0;
            release(textures[i]);
            textures[i] = 0;
        }
        release(backBuffer);
        backBuffer = 0;
    }

    /** NT handles of the shared textures (owned here; an import into Vulkan does not take them). */
    @Override public int memoryHandleType() { return org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT; }
    @Override public long textureHandle(int index) { return textureHandles[index]; }
    @Override public long renderFenceHandle() { return renderFenceHandle; }
    @Override public long copyFenceHandle() { return copyFenceHandle; }
    @Override public int width() { return width; }
    @Override public int height() { return height; }

    /**
     * Queues, on the D3D11 side: wait for Vulkan's {@code renderValue}, copy texture {@code index}
     * into the back buffer, signal the copy fence, present. Returns the copy fence value Vulkan has to
     * wait for before drawing into {@code index} again, or -1 when presenting failed.
     */
    @Override
    public long present(int index, long renderValue, boolean vsync) {
        if (closed) return -1;
        call(context4, CONTEXT4_WAIT, renderFence, renderValue);
        call(context, CONTEXT_COPY_RESOURCE, backBuffer, textures[index]);
        long value = ++copyValue;
        call(context4, CONTEXT4_SIGNAL, copyFence, value);
        int hr = call(swapchain, SWAPCHAIN_PRESENT, vsync ? 1 : 0, !vsync && tearing ? DXGI_PRESENT_ALLOW_TEARING : 0);
        if (failed(hr)) {
            LOGGER.warn("DXGI Present failed: {}", hex(hr));
            return -1;
        }
        return value;
    }

    /** Resizes the swapchain and recreates the shared textures; the caller re-imports them into Vulkan. */
    @Override
    public void resize(int width, int height) {
        releaseTargets();
        call(context, CONTEXT_FLUSH);
        check(call(swapchain, SWAPCHAIN_RESIZE_BUFFERS, 0, width, height, 0, swapchainFlags()), "ResizeBuffers");
        createTargets(width, height);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        releaseTargets();
        if (context != 0) call(context, CONTEXT_FLUSH);
        release(swapchain);
        closeHandle(renderFenceHandle);
        closeHandle(copyFenceHandle);
        release(renderFence);
        release(copyFence);
        release(context4);
        release(device5);
        release(context);
        release(device);
        release(factory);
        swapchain = context = context4 = device = device5 = factory = renderFence = copyFence = 0;
    }

    private static long clear(long pointer) {
        MemoryUtil.memPutAddress(pointer, 0);
        return pointer;
    }

    private static void check(int hr, String what) {
        if (failed(hr)) throw new IllegalStateException(what + " failed: " + hex(hr));
    }
}

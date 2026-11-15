package net.vulkanmod.vulkan.present;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.WinBase;

import java.util.UUID;

/**
 * Minimal COM and Win32 calls for the DXGI presentation path. Vtable slots and structure layouts are
 * taken from the Windows SDK 10.0.28000 headers (d3d12.h, dxgi*.h). Every argument is passed as a
 * 64-bit value: on Windows x64 integer arguments occupy one register or stack slot each, and the
 * callee reads only the low half of a 32-bit parameter. Render thread only.
 */
final class Win32Com {
    private Win32Com() {}

    static final int S_OK = 0;

    /** The address of {@code name} in {@code library}, loading the library if needed; 0 when absent. */
    static long function(String library, String name) {
        long module = WinBase.GetModuleHandle(library);
        if (module == 0) module = WinBase.LoadLibrary(library);
        return module == 0 ? 0 : WinBase.GetProcAddress(module, name);
    }

    /** Calls slot {@code index} of the COM object {@code object} with the given arguments; returns the low 32 bits. */
    static int call(long object, int index, long... args) {
        if (object == 0) throw new IllegalStateException("COM call on a null object, slot " + index);
        long function = MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(object) + 8L * index);
        return invoke(function, object, args);
    }

    /** Calls a plain exported function. */
    static int callFunction(long function, long... args) {
        if (function == 0) throw new IllegalStateException("Missing Win32 function");
        return switch (args.length) {
            case 1 -> JNI.callPI(args[0], function);
            case 2 -> JNI.callPPI(args[0], args[1], function);
            case 3 -> JNI.callPPPI(args[0], args[1], args[2], function);
            case 4 -> JNI.callPPPPI(args[0], args[1], args[2], args[3], function);
            default -> ffiCall(function, args);
        };
    }

    private static int invoke(long function, long self, long[] a) {
        return switch (a.length) {
            case 0 -> JNI.callPI(self, function);
            case 1 -> JNI.callPPI(self, a[0], function);
            case 2 -> JNI.callPPPI(self, a[0], a[1], function);
            case 3 -> JNI.callPPPPI(self, a[0], a[1], a[2], function);
            case 4 -> JNI.callPPPPPI(self, a[0], a[1], a[2], a[3], function);
            case 5 -> JNI.callPPPPPPI(self, a[0], a[1], a[2], a[3], a[4], function);
            default -> {
                long[] all = new long[a.length + 1];
                all[0] = self;
                System.arraycopy(a, 0, all, 1, a.length);
                yield ffiCall(function, all);
            }
        };
    }

    /** Any argument count through libffi, every argument a 64-bit value; used for setup calls only. */
    private static int ffiCall(long function, long[] args) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var cif = org.lwjgl.system.libffi.FFICIF.malloc(stack);
            var types = stack.mallocPointer(args.length);
            var values = stack.mallocPointer(args.length);
            var storage = stack.mallocLong(args.length);
            for (int i = 0; i < args.length; i++) {
                types.put(i, org.lwjgl.system.libffi.LibFFI.ffi_type_uint64.address());
                storage.put(i, args[i]);
                values.put(i, MemoryUtil.memAddress(storage) + 8L * i);
            }
            int status = org.lwjgl.system.libffi.LibFFI.ffi_prep_cif(cif, org.lwjgl.system.libffi.LibFFI.FFI_DEFAULT_ABI,
                    org.lwjgl.system.libffi.LibFFI.ffi_type_sint32, types);
            if (status != org.lwjgl.system.libffi.LibFFI.FFI_OK) throw new IllegalStateException("ffi_prep_cif failed: " + status);
            var result = stack.malloc(8);
            org.lwjgl.system.libffi.LibFFI.ffi_call(cif, function, result, values);
            return result.getInt(0);
        }
    }

    /** Releases a COM reference (IUnknown::Release, slot 2); null is ignored. */
    static void release(long object) {
        if (object != 0) call(object, 2);
    }

    /** IUnknown::QueryInterface (slot 0); 0 when the object does not implement {@code iid}. */
    static long query(long object, String iid) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.mallocPointer(1).address();
            MemoryUtil.memPutAddress(out, 0);
            int hr = call(object, 0, guid(stack, iid), out);
            return hr == S_OK ? MemoryUtil.memGetAddress(out) : 0;
        }
    }

    /** A GUID in memory (Data1 little-endian 32 bit, Data2/Data3 16 bit, Data4 as bytes). */
    static long guid(MemoryStack stack, String text) {
        UUID uuid = UUID.fromString(text);
        long address = stack.nmalloc(4, 16);
        long msb = uuid.getMostSignificantBits(), lsb = uuid.getLeastSignificantBits();
        MemoryUtil.memPutInt(address, (int) (msb >>> 32));
        MemoryUtil.memPutShort(address + 4, (short) (msb >>> 16));
        MemoryUtil.memPutShort(address + 6, (short) msb);
        for (int i = 0; i < 8; i++) MemoryUtil.memPutByte(address + 8 + i, (byte) (lsb >>> (56 - 8 * i)));
        return address;
    }

    static void closeHandle(long handle) {
        if (handle == 0) return;
        long close = function("kernel32.dll", "CloseHandle");
        if (close != 0) JNI.callPI(handle, close);
    }

    static boolean failed(int hr) {
        return hr < 0;
    }

    static String hex(int hr) {
        return String.format("0x%08X", hr);
    }
}

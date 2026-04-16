package com.exeopener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parses the Windows PE (Portable Executable) header from an EXE input stream.
 *
 * PE layout (simplified):
 *   [0x00] DOS header (MZ magic + e_lfanew at offset 0x3C)
 *   [e_lfanew] PE signature "PE\0\0"
 *   [+4]  COFF File Header (20 bytes)
 *   [+24] Optional Header (variable, PE32 or PE32+)
 */
public class ExeAnalyzer {

    // COFF machine types
    private static final int IMAGE_FILE_MACHINE_I386  = 0x014c;
    private static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;
    private static final int IMAGE_FILE_MACHINE_ARM   = 0x01c0;
    private static final int IMAGE_FILE_MACHINE_ARM64 = 0xAA64;
    private static final int IMAGE_FILE_MACHINE_IA64  = 0x0200;

    // Optional header magic
    private static final int IMAGE_NT_OPTIONAL_HDR32_MAGIC = 0x10b;
    private static final int IMAGE_NT_OPTIONAL_HDR64_MAGIC = 0x20b;

    // Subsystem values
    private static final int IMAGE_SUBSYSTEM_NATIVE          = 1;
    private static final int IMAGE_SUBSYSTEM_WINDOWS_GUI     = 2;
    private static final int IMAGE_SUBSYSTEM_WINDOWS_CUI     = 3;
    private static final int IMAGE_SUBSYSTEM_WINDOWS_CE_GUI  = 9;

    // Max bytes we need to read (DOS header + some slack for e_lfanew target)
    private static final int READ_LIMIT = 4096;

    private final byte[] data;
    private final int dataLen;

    public ExeAnalyzer(InputStream is) throws IOException {
        byte[] buf = new byte[READ_LIMIT];
        int total = 0, read;
        while (total < READ_LIMIT && (read = is.read(buf, total, READ_LIMIT - total)) != -1) {
            total += read;
        }
        this.data = buf;
        this.dataLen = total;
    }

    public ExeInfo analyze() throws IOException {
        ExeInfo info = new ExeInfo();
        info.fileSize = dataLen; // This is just the portion we read; actual size unknown from stream alone

        if (dataLen < 2) throw new IOException("File too small to be a valid EXE");

        // Check MZ magic ("MZ" = 0x4D5A)
        if ((data[0] & 0xFF) != 0x4D || (data[1] & 0xFF) != 0x5A) {
            throw new IOException("Not a valid EXE — missing MZ header");
        }
        info.hasMzHeader = true;

        if (dataLen < 0x40) throw new IOException("File too small to contain PE offset");

        // e_lfanew is a 32-bit LE value at offset 0x3C
        int peOffset = readInt32LE(0x3C);
        if (peOffset < 0 || peOffset + 24 > dataLen) {
            throw new IOException("PE header offset 0x" + Integer.toHexString(peOffset) + " out of range");
        }

        // PE signature: "PE\0\0"
        if ((data[peOffset]     & 0xFF) != 'P' ||
            (data[peOffset + 1] & 0xFF) != 'E' ||
            (data[peOffset + 2] & 0xFF) != 0x00||
            (data[peOffset + 3] & 0xFF) != 0x00) {
            throw new IOException("PE signature not found at offset 0x" + Integer.toHexString(peOffset));
        }
        info.hasPeSignature = true;

        // COFF File Header starts at peOffset + 4
        int coffOffset = peOffset + 4;
        int machine = readUInt16LE(coffOffset);
        info.machineCode = machine;
        info.architecture = machineToString(machine);

        int numberOfSections = readUInt16LE(coffOffset + 2);
        info.numberOfSections = numberOfSections;

        int characteristics = readUInt16LE(coffOffset + 18);
        info.isDll = (characteristics & 0x2000) != 0;
        info.is32Bit = (characteristics & 0x0100) != 0;

        int sizeOfOptionalHeader = readUInt16LE(coffOffset + 16);
        info.hasOptionalHeader = sizeOfOptionalHeader > 0;

        // Optional Header starts at peOffset + 4 + 20 = peOffset + 24
        if (sizeOfOptionalHeader > 0 && peOffset + 24 + 2 <= dataLen) {
            int optOffset = peOffset + 24;
            int optMagic = readUInt16LE(optOffset);
            if (optMagic == IMAGE_NT_OPTIONAL_HDR32_MAGIC) {
                info.peFormat = "PE32 (32-bit)";
                if (optOffset + 68 <= dataLen) {
                    int subsystem = readUInt16LE(optOffset + 68);
                    info.subsystemCode = subsystem;
                    info.subsystem = subsystemToString(subsystem);
                }
            } else if (optMagic == IMAGE_NT_OPTIONAL_HDR64_MAGIC) {
                info.peFormat = "PE32+ (64-bit)";
                if (optOffset + 68 <= dataLen) {
                    int subsystem = readUInt16LE(optOffset + 68);
                    info.subsystemCode = subsystem;
                    info.subsystem = subsystemToString(subsystem);
                }
            } else {
                info.peFormat = "Unknown optional header magic: 0x" + Integer.toHexString(optMagic);
            }
        }

        return info;
    }

    // ---- Helpers ----

    private int readUInt16LE(int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int readInt32LE(int offset) {
        return (data[offset]     & 0xFF)        |
               ((data[offset+1] & 0xFF) << 8)   |
               ((data[offset+2] & 0xFF) << 16)  |
               ((data[offset+3] & 0xFF) << 24);
    }

    private static String machineToString(int machine) {
        switch (machine) {
            case IMAGE_FILE_MACHINE_I386:  return "x86 (32-bit)";
            case IMAGE_FILE_MACHINE_AMD64: return "x86-64 (64-bit)";
            case IMAGE_FILE_MACHINE_ARM:   return "ARM (32-bit)";
            case IMAGE_FILE_MACHINE_ARM64: return "ARM64 (64-bit)";
            case IMAGE_FILE_MACHINE_IA64:  return "IA-64 (Itanium)";
            default: return "Unknown (0x" + Integer.toHexString(machine) + ")";
        }
    }

    private static String subsystemToString(int subsystem) {
        switch (subsystem) {
            case IMAGE_SUBSYSTEM_NATIVE:         return "Native";
            case IMAGE_SUBSYSTEM_WINDOWS_GUI:    return "Windows GUI app";
            case IMAGE_SUBSYSTEM_WINDOWS_CUI:    return "Windows Console app";
            case IMAGE_SUBSYSTEM_WINDOWS_CE_GUI: return "Windows CE GUI";
            default: return "Other (" + subsystem + ")";
        }
    }

    // ---- Result POJO ----

    public static class ExeInfo {
        public boolean hasMzHeader;
        public boolean hasPeSignature;
        public boolean hasOptionalHeader;
        public boolean isDll;
        public boolean is32Bit;
        public String architecture = "Unknown";
        public String subsystem    = "Unknown";
        public String peFormat     = "Unknown";
        public int machineCode;
        public int subsystemCode;
        public int numberOfSections;
        public long fileSize;

        public String toDisplayString() {
            return "MZ header    : " + (hasMzHeader     ? "Yes" : "No")  + "\n" +
                   "PE signature : " + (hasPeSignature  ? "Yes" : "No")  + "\n" +
                   "PE format    : " + peFormat                           + "\n" +
                   "Architecture : " + architecture                       + "\n" +
                   "Subsystem    : " + subsystem                          + "\n" +
                   "Sections     : " + numberOfSections                   + "\n" +
                   "Type         : " + (isDll ? "DLL" : "Executable");
        }

        /** Returns true if this EXE might run under Wine for Android (x86 or x86-64). */
        public boolean isWineCompatible() {
            return machineCode == IMAGE_FILE_MACHINE_I386 || machineCode == IMAGE_FILE_MACHINE_AMD64;
        }
    }
}

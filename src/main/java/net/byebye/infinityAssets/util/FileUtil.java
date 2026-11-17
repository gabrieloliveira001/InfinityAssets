package net.byebye.infinityAssets.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class FileUtil {
    private FileUtil() {
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Copies a directory tree into destination, overwriting files. */
    public static void copyTree(File source, File destination) throws IOException {
        if (!source.exists()) return;
        Path src = source.toPath();
        Path dst = destination.toPath();
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static List<File> listFilesRecursively(File dir, String... extensions) {
        List<File> out = new ArrayList<>();
        collect(dir, out, extensions);
        out.sort(null);
        return out;
    }

    private static void collect(File f, List<File> out, String[] extensions) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) collect(c, out, extensions);
            return;
        }
        String name = f.getName().toLowerCase();
        if (extensions.length == 0) {
            out.add(f);
            return;
        }
        for (String ext : extensions) {
            if (name.endsWith(ext)) {
                out.add(f);
                return;
            }
        }
    }

    public static void zipDirectory(File dir, File zipFile, List<String> ignoredExtensions) throws IOException {
        Path root = dir.toPath();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            for (File f : listFilesRecursively(dir)) {
                String rel = root.relativize(f.toPath()).toString().replace(File.separatorChar, '/');
                String lower = rel.toLowerCase();
                boolean skip = lower.endsWith("desktop.ini") || lower.endsWith(".ds_store") || lower.endsWith("thumbs.db");
                for (String ext : ignoredExtensions) {
                    if (lower.endsWith("." + ext.toLowerCase())) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;
                ZipEntry entry = new ZipEntry(rel);
                entry.setTime(0L); // deterministic zip -> stable SHA-1 between identical builds
                zos.putNextEntry(entry);
                Files.copy(f.toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    public static void unzip(File zipFile, File destDir) throws IOException {
        String destCanonical = destDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, ze.getName());
                if (!newFile.getCanonicalPath().startsWith(destCanonical)) {
                    throw new IOException("Entrada de zip fora da pasta de destino: " + ze.getName());
                }
                if (ze.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    Files.copy(zis, newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public static byte[] sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        return digest.digest();
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

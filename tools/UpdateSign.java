import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public class UpdateSign {
    static final String PREFIX = "AbsentMotion update v1\n";
    static final long MAX_FILE_BYTES = 400L << 20;
    static final int MAX_LIST_BYTES = 64 << 10;
    static final java.util.regex.Pattern ENTRY = java.util.regex.Pattern.compile("\"name\": \"([^\"]*)\", \"size\": (\\d+), \"sha256\": \"([0-9a-f]{64})\"");

    static void rules(java.util.List<String> names, java.util.List<Long> sizes, int listBytes) {
        if (names.isEmpty()) throw new IllegalStateException("the list has no files");
        if (listBytes > MAX_LIST_BYTES) throw new IllegalStateException("the list is larger than " + MAX_LIST_BYTES + " bytes");
        if (new java.util.HashSet<>(names).size() != names.size()) throw new IllegalStateException("the list repeats a file name");
        for (int i = 0; i < names.size(); i++) {
            if (!names.get(i).matches("[A-Za-z0-9._-]+")) throw new IllegalStateException("unsafe file name: " + names.get(i));
            if (sizes.get(i) < 1 || sizes.get(i) > MAX_FILE_BYTES) throw new IllegalStateException("file size out of range: " + names.get(i));
        }
    }

    public static void main(String[] a) throws Exception {
        switch (a.length == 0 ? "" : a[0]) {
            case "keygen" -> keygen(Path.of(a[1]), Path.of(a[2]));
            case "manifest" -> manifest(a[1], Integer.parseInt(a[2]), Path.of(a[3]), Path.of(a[4]), java.util.Arrays.copyOfRange(a, 5, a.length));
            case "verify" -> {
                try {
                    System.exit(verify(Path.of(a[1]), Path.of(a[2]), Files.readString(Path.of(a[3])).trim()) ? 0 : 1);
                } catch (IllegalStateException e) {
                    System.err.println("UpdateSign: " + e.getMessage());
                    System.exit(1);
                }
            }
            default -> {
                System.err.println("usage: UpdateSign.java keygen <private-out> <public-out>");
                System.err.println("       UpdateSign.java manifest <version> <min-updater> <private-key> <out-dir> <file>...");
                System.err.println("       UpdateSign.java verify <manifest> <signature> <public-key-file>");
                System.exit(2);
            }
        }
    }

    static void keygen(Path privateOut, Path publicOut) throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(privateOut, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
        Files.writeString(publicOut, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n");
        try { Files.setPosixFilePermissions(privateOut, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void manifest(String version, int minUpdater, Path privateKey, Path outDir, String[] files) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\n \"schema\": 1,\n \"version\": \"").append(version).append("\",\n \"minUpdater\": ").append(minUpdater).append(",\n \"files\": [");
        String[] sorted = files.clone();
        java.util.Arrays.sort(sorted, java.util.Comparator.comparing(f -> Path.of(f).getFileName().toString()));
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<Long> sizes = new java.util.ArrayList<>();
        for (String f : sorted) { names.add(Path.of(f).getFileName().toString()); sizes.add(Files.size(Path.of(f))); }
        for (int i = 0; i < sorted.length; i++) {
            Path file = Path.of(sorted[i]);
            json.append(i == 0 ? "\n" : ",\n").append("  {\"name\": \"").append(file.getFileName()).append("\", \"size\": ").append(Files.size(file))
                .append(", \"sha256\": \"").append(sha256(file)).append("\"}");
        }
        json.append("\n ]\n}\n");
        byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);
        rules(names, sizes, raw.length);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(privateKey).trim()))));
        signer.update(PREFIX.getBytes(StandardCharsets.UTF_8));
        signer.update(raw);
        Files.write(outDir.resolve("update-manifest.json"), raw);
        Files.write(outDir.resolve("update-manifest.json.sig"), signer.sign());
    }

    static boolean verify(Path manifest, Path signature, String publicKey) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
        verifier.update(PREFIX.getBytes(StandardCharsets.UTF_8));
        byte[] raw = Files.readAllBytes(manifest);
        verifier.update(raw);
        if (!verifier.verify(Files.readAllBytes(signature))) return false;
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<Long> sizes = new java.util.ArrayList<>();
        java.util.regex.Matcher m = ENTRY.matcher(new String(raw, StandardCharsets.UTF_8));
        while (m.find()) { names.add(m.group(1)); sizes.add(Long.parseLong(m.group(2))); }
        rules(names, sizes, raw.length);
        return true;
    }
}

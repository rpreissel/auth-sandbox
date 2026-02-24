package dev.authsandbox.authservice.security;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

public final class KeyLoader {

    private KeyLoader() {}

    private static final ResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    public static PrivateKey loadPrivateKey(String path) throws Exception {
        String pem = readPem(path);
        String stripped = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(stripped);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    public static PublicKey loadPublicKey(String path) throws Exception {
        return parsePublicKey(readPem(path));
    }

    /**
     * Parses an RSA public key from a PEM-encoded string.
     * Accepts both file-loaded and inline PEM strings.
     */
    public static PublicKey parsePublicKey(String pem) throws GeneralSecurityException {
        String stripped = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(stripped);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private static String readPem(String path) throws Exception {
        Resource resource = RESOURCE_LOADER.getResource(
                path.startsWith("/") ? "file:" + path : path
        );
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

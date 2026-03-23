package com.anthropic.claude.intellij.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Secure storage for the Anthropic API key using IntelliJ's PasswordSafe.
 * The key is stored in the system keychain (macOS Keychain, Windows Credential Manager,
 * or KDE Wallet / GNOME Keyring on Linux) when available, falling back to an
 * encrypted IDE-managed store.
 */
public final class SecureApiKeyStore {

    private static final Logger LOG = Logger.getInstance(SecureApiKeyStore.class);

    private static final String SERVICE_NAME = "com.anthropic.claude.intellij";
    private static final String KEY_NAME = "apiKey";

    private SecureApiKeyStore() {
        // utility class
    }

    /**
     * Retrieves the stored API key, or {@code null} if none has been saved.
     */
    @Nullable
    public static String getApiKey() {
        try {
            CredentialAttributes attributes = createAttributes();
            Credentials credentials = PasswordSafe.getInstance().get(attributes);
            if (credentials != null) {
                return credentials.getPasswordAsString();
            }
        } catch (Exception e) {
            LOG.error("Failed to retrieve API key from PasswordSafe", e);
        }
        return null;
    }

    /**
     * Stores the API key. Pass {@code null} or an empty string to clear it.
     */
    public static void setApiKey(@Nullable String apiKey) {
        try {
            CredentialAttributes attributes = createAttributes();
            if (apiKey == null || apiKey.isEmpty()) {
                PasswordSafe.getInstance().set(attributes, null);
            } else {
                Credentials credentials = new Credentials(KEY_NAME, apiKey);
                PasswordSafe.getInstance().set(attributes, credentials);
            }
        } catch (Exception e) {
            LOG.error("Failed to store API key in PasswordSafe", e);
        }
    }

    /**
     * Returns {@code true} if an API key is currently stored.
     */
    public static boolean hasApiKey() {
        String key = getApiKey();
        return key != null && !key.isEmpty();
    }

    private static CredentialAttributes createAttributes() {
        return new CredentialAttributes(
                CredentialAttributesKt.generateServiceName(SERVICE_NAME, KEY_NAME)
        );
    }
}

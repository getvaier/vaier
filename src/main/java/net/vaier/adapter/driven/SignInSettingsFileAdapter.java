package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.IdentityProvider;
import net.vaier.domain.ProviderCredentials;
import net.vaier.domain.SignInSettings;
import net.vaier.domain.port.ForPersistingSignInSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ${VAIER_CONFIG_PATH}/sign-in-providers.env}: {@code KEY=value} lines that dex-init and
 * oauth2-proxy-init parse with sed — never source — as root. Only Vaier writes it, so the last read or
 * write is held in memory and the forward-auth path never touches the disk for it.
 */
@Component
@Slf4j
public class SignInSettingsFileAdapter implements ForPersistingSignInSettings {

    static final String FILE_NAME = "sign-in-providers.env";
    private static final String DOOR_KEY = "FIRST_RUN_DOOR";

    private final Path file;
    private volatile SignInSettings cached;

    @Autowired
    public SignInSettingsFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public SignInSettingsFileAdapter(String configDir) {
        this.file = Path.of(configDir, FILE_NAME);
    }

    @Override
    public SignInSettings read() {
        SignInSettings known = cached;
        if (known == null) {
            known = load();
            cached = known;
        }
        return known;
    }

    @Override
    public synchronized void save(SignInSettings settings) {
        List<String> lines = new ArrayList<>(List.of(
            "# Sign-in providers added from Vaier's Settings. Read by dex-init and oauth2-proxy-init;",
            "# a provider whose client id and secret are both set in .env ignores its lines here.",
            DOOR_KEY + "=" + (settings.firstRunDoorOpen() ? "open" : "closed")));
        settings.providers().forEach((provider, credentials) -> {
            lines.add(key(provider, "CLIENT_ID") + "=" + credentials.clientId());
            lines.add(key(provider, "CLIENT_SECRET") + "=" + credentials.clientSecret());
        });
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), FILE_NAME, ".tmp");
            SecureFilePermissions.lockDownFile(temp);
            Files.write(temp, lines);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
        cached = settings;
    }

    private SignInSettings load() {
        if (!Files.exists(file)) {
            return SignInSettings.none();
        }
        try {
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) values.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
            Map<IdentityProvider, ProviderCredentials> providers = new EnumMap<>(IdentityProvider.class);
            for (IdentityProvider provider : IdentityProvider.values()) {
                String id = values.get(key(provider, "CLIENT_ID"));
                String secret = values.get(key(provider, "CLIENT_SECRET"));
                if (id != null && !id.isBlank() && secret != null && !secret.isBlank()) {
                    providers.put(provider, new ProviderCredentials(id, secret));
                }
            }
            return new SignInSettings(providers, "open".equals(values.get(DOOR_KEY)));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
            return SignInSettings.none();
        }
    }

    private static String key(IdentityProvider provider, String suffix) {
        return provider.name() + "_" + suffix;
    }
}

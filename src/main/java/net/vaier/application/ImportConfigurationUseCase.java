package net.vaier.application;

import java.util.List;

public interface ImportConfigurationUseCase {
    ImportResult importConfiguration(String jsonContent);

    record ImportResult(boolean success, String message, List<String> warnings) {}
}

package net.vaier.application;

import java.util.Optional;

public interface GeneratePeerSetupScriptUseCase {

    Optional<String> generateSetupScript(String peerName, String serverUrl, String serverPort);
}

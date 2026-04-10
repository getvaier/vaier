package net.vaier.application;

public interface CompleteSetupUseCase {
    void completeSetup(String domain, String awsKey, String awsSecret, String acmeEmail,
                       String adminUsername, String adminPassword);
}

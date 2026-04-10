package net.vaier.domain.port;

import java.util.List;

public interface ForValidatingAwsCredentials {
    List<String> listHostedZones(String awsKey, String awsSecret);
}

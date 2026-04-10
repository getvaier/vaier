package net.vaier.application;

import java.util.List;

public interface ValidateAwsCredentialsUseCase {
    List<String> validateAndListZones(String awsKey, String awsSecret);
}

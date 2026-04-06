package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.UnignorePublishableServiceUseCase;
import net.vaier.domain.port.ForManagingIgnoredServices;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UnignorePublishableServiceService implements UnignorePublishableServiceUseCase {

    private final ForManagingIgnoredServices forManagingIgnoredServices;

    @Override
    public void unignoreService(String key) {
        forManagingIgnoredServices.unignoreService(key);
    }
}

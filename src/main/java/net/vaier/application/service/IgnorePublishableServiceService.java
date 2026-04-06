package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.IgnorePublishableServiceUseCase;
import net.vaier.domain.port.ForManagingIgnoredServices;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IgnorePublishableServiceService implements IgnorePublishableServiceUseCase {

    private final ForManagingIgnoredServices forManagingIgnoredServices;

    @Override
    public void ignoreService(String key) {
        forManagingIgnoredServices.ignoreService(key);
    }
}

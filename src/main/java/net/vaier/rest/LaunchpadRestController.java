package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import net.vaier.application.GetLaunchpadServicesUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/launchpad")
@RequiredArgsConstructor
public class LaunchpadRestController {

    private final GetLaunchpadServicesUseCase getLaunchpadServicesUseCase;

    @GetMapping("/services")
    public List<LaunchpadServiceUco> getServices() {
        return getLaunchpadServicesUseCase.getLaunchpadServices();
    }
}

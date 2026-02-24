package com.wireweave.rest;

import com.wireweave.application.GetHostedServicesUseCase;
import com.wireweave.application.GetHostedServicesUseCase.HostedServiceUco;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hosted-services")
public class HostedServiceRestController {

    private final GetHostedServicesUseCase getHostedServicesUseCase;

    public HostedServiceRestController(GetHostedServicesUseCase getHostedServicesUseCase) {
        this.getHostedServicesUseCase = getHostedServicesUseCase;
    }

    @GetMapping("/discover")
    public List<HostedServiceUco> getHostedServices() {
        return getHostedServicesUseCase.getHostedServices();
    }
}

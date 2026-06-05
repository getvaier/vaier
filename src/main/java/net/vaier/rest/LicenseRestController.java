package net.vaier.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import net.vaier.application.GetLicenseStatusUseCase;
import net.vaier.application.GetLicenseStatusUseCase.LicenseStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the current licensing state so the UI can decide whether to render Enterprise features.
 * Deliberately not gated by {@link RequiresEnterprise} — a Community instance must be able to read
 * its own (unlicensed) status to know <em>not</em> to show those features.
 */
@RestController
@RequestMapping("/license")
@Tag(name = "Licensing", description = "Edition and Enterprise licence status")
public class LicenseRestController {

    private final GetLicenseStatusUseCase getLicenseStatus;

    public LicenseRestController(GetLicenseStatusUseCase getLicenseStatus) {
        this.getLicenseStatus = getLicenseStatus;
    }

    @GetMapping
    @Operation(summary = "Current edition and Enterprise licence status")
    public ResponseEntity<LicenseStatus> getLicense() {
        return ResponseEntity.ok(getLicenseStatus.status());
    }
}

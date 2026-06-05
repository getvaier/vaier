package net.vaier.rest;

import net.vaier.application.GetLicenseStatusUseCase;
import net.vaier.application.GetLicenseStatusUseCase.LicenseStatus;
import net.vaier.domain.Edition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LicenseRestControllerTest {

    @Mock GetLicenseStatusUseCase getLicenseStatusUseCase;

    @InjectMocks LicenseRestController controller;

    @Test
    void getLicense_returnsCurrentStatus() {
        LicenseStatus status = new LicenseStatus(Edition.ENTERPRISE, true, "Acme Ltd",
            Instant.parse("2027-01-01T00:00:00Z"));
        when(getLicenseStatusUseCase.status()).thenReturn(status);

        ResponseEntity<LicenseStatus> response = controller.getLicense();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(status);
    }
}

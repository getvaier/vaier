package net.vaier.rest;

import net.vaier.application.AddSignInProviderUseCase;
import net.vaier.application.GetSignInProvidersUseCase;
import net.vaier.application.GetSignInProvidersUseCase.SignInOverview;
import net.vaier.domain.IdentityProvider;
import net.vaier.domain.ProviderStanding;
import net.vaier.domain.SignInApplyOutcome;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Settings → Sign-in (#264): add an identity provider without editing .env. Admin-only through the console's
 * forward-auth chain like every other Settings route. The client secret goes in and never comes back out.
 */
@RestController
@RequestMapping("/settings/sign-in")
public class SignInRestController {

    private final GetSignInProvidersUseCase getSignInProvidersUseCase;
    private final AddSignInProviderUseCase addSignInProviderUseCase;

    public SignInRestController(GetSignInProvidersUseCase getSignInProvidersUseCase,
                                AddSignInProviderUseCase addSignInProviderUseCase) {
        this.getSignInProvidersUseCase = getSignInProvidersUseCase;
        this.addSignInProviderUseCase = addSignInProviderUseCase;
    }

    @GetMapping
    public ResponseEntity<SignInResponse> getSignIn() {
        SignInOverview overview = getSignInProvidersUseCase.getSignInProviders();
        return ResponseEntity.ok(new SignInResponse(overview.redirectUri(), overview.firstRunDoorOpen(),
            overview.providers().stream().map(ProviderResponse::of).toList()));
    }

    /** Waits for the apply — seconds — so the answer is the outcome, not a promise of one. */
    @PutMapping("/{provider}")
    public ResponseEntity<OutcomeResponse> addProvider(@PathVariable String provider,
                                                       @RequestBody ProviderRequest request) {
        IdentityProvider identityProvider = IdentityProvider.fromConnectorId(provider)
            .orElseThrow(() -> new IllegalArgumentException("Unknown sign-in provider: " + provider));
        SignInApplyOutcome outcome = addSignInProviderUseCase.addSignInProvider(identityProvider,
            request.clientId(), request.clientSecret());
        return ResponseEntity.ok(new OutcomeResponse(outcome.applied(), outcome.message()));
    }

    public record SignInResponse(String redirectUri, boolean firstRunDoorOpen, List<ProviderResponse> providers) {}

    public record ProviderResponse(String id, String name, String consoleUrl, String source, String clientId) {
        static ProviderResponse of(ProviderStanding standing) {
            IdentityProvider p = standing.provider();
            return new ProviderResponse(p.connectorId(), p.displayName(), p.consoleUrl(),
                standing.source().name(), standing.clientId());
        }
    }

    public record ProviderRequest(String clientId, String clientSecret) {}

    public record OutcomeResponse(boolean applied, String message) {}
}

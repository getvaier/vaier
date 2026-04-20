package net.vaier.rest;

import net.vaier.application.AddUserUseCase;
import net.vaier.application.ChangePasswordUseCase;
import net.vaier.application.DeleteUserUseCase;
import net.vaier.application.GetUsersUseCase;
import net.vaier.application.UpdateUserDisplayNameUseCase;
import net.vaier.application.UpdateUserEmailUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class AuthRestController {

    private final ConfigResolver configResolver;
    private final GetUsersUseCase getUsersUseCase;
    private final AddUserUseCase addUserUseCase;
    private final DeleteUserUseCase deleteUserUseCase;
    private final ChangePasswordUseCase changePasswordUseCase;
    private final UpdateUserEmailUseCase updateUserEmailUseCase;
    private final UpdateUserDisplayNameUseCase updateUserDisplayNameUseCase;

    public AuthRestController(ConfigResolver configResolver,
                              GetUsersUseCase getUsersUseCase,
                              AddUserUseCase addUserUseCase,
                              DeleteUserUseCase deleteUserUseCase,
                              ChangePasswordUseCase changePasswordUseCase,
                              UpdateUserEmailUseCase updateUserEmailUseCase,
                              UpdateUserDisplayNameUseCase updateUserDisplayNameUseCase) {
        this.configResolver = configResolver;
        this.getUsersUseCase = getUsersUseCase;
        this.addUserUseCase = addUserUseCase;
        this.deleteUserUseCase = deleteUserUseCase;
        this.changePasswordUseCase = changePasswordUseCase;
        this.updateUserEmailUseCase = updateUserEmailUseCase;
        this.updateUserDisplayNameUseCase = updateUserDisplayNameUseCase;
    }

    @GetMapping
    public List<User> getUsers() {
        return getUsersUseCase.getUsers();
    }

    @PostMapping
    public ResponseEntity<String> addUser(@RequestBody AddUserRequest request) {
        try {
            addUserUseCase.addUser(request.username(), request.password(), request.email(), request.displayname());
            return ResponseEntity.ok("User added successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<String> deleteUser(@PathVariable String username) {
        try {
            deleteUserUseCase.deleteUser(username);
            return ResponseEntity.ok("User deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{username}/password")
    public ResponseEntity<String> changePassword(
        @PathVariable String username,
        @RequestBody ChangePasswordRequest request
    ) {
        try {
            changePasswordUseCase.changePassword(username, request.newPassword());
            return ResponseEntity.ok("Password changed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{username}/email")
    public ResponseEntity<String> updateEmail(
        @PathVariable String username,
        @RequestBody UpdateEmailRequest request
    ) {
        try {
            updateUserEmailUseCase.updateEmail(username, request.email());
            return ResponseEntity.ok("Email updated successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{username}/displayname")
    public ResponseEntity<String> updateDisplayName(
        @PathVariable String username,
        @RequestBody UpdateDisplayNameRequest request
    ) {
        try {
            updateUserDisplayNameUseCase.updateDisplayName(username, request.displayname());
            return ResponseEntity.ok("Display name updated successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> getMe(
            @RequestHeader(value = "Remote-User", required = false) String username,
            @RequestHeader(value = "Remote-Name", required = false) String displayname,
            @RequestHeader(value = "Remote-Email", required = false) String email) {
        String domain = configResolver.getDomain();
        String logoutUrl = (domain != null && !domain.isBlank())
                ? "https://login." + domain + "/logout"
                : null;
        return ResponseEntity.ok(new MeResponse(username, displayname, email, logoutUrl));
    }

    public record AddUserRequest(String username, String password, String email, String displayname) {}
    public record ChangePasswordRequest(String newPassword) {}
    public record UpdateEmailRequest(String email) {}
    public record UpdateDisplayNameRequest(String displayname) {}
    public record MeResponse(String username, String displayname, String email, String logoutUrl) {}
}
